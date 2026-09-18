package org.hayden.backend.qdrant;

import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.JsonWithInt.ListValue;
import io.qdrant.client.grpc.JsonWithInt.NullValue;
import io.qdrant.client.grpc.JsonWithInt.Struct;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.PointId;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.UpsertPoints;
import io.qdrant.client.grpc.Points.Vector;
import io.qdrant.client.grpc.Points.Vectors;
import io.qdrant.client.grpc.Points.NamedVectors;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hayden.ingest.IngestException;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Multivector page upserts over Qdrant's gRPC port.
 *
 * <p>Why: the REST upsert writes every float as decimal text (about 20 MB
 * of JSON per 4-page call at 1,280 x 320 per page) and Qdrant parses it
 * back — 350-550% of Qdrant's CPU and most of the queue workers' wall time
 * on the R530 once the sidecars were no longer the limiter (2026-09-18).
 * Over gRPC the vectors travel as packed float32 in protobuf on one
 * multiplexed HTTP/2 connection: nothing is formatted or parsed.
 *
 * <p>Scope: ONLY {@link #upsert}. Collections, deletes, searches and the
 * chunk (text) upserts stay on {@link QdrantClient}'s REST path; Qdrant
 * serves both ports on the same data, so the two transports mix freely and
 * no other client is affected. Selected by
 * {@code ingest.qdrant.upsert-transport} (default {@code grpc}); the host
 * defaults to the REST URL's host and the port to Qdrant's default 6334.
 *
 * <p>Sizing: Qdrant's {@code service.max_request_size_mb} (32 by default)
 * bounds one call. A 4-page batch of 1,344 x 320 float32 vectors is about
 * 7 MB; raise that setting before raising
 * {@code ingest.qdrant.multivector-upsert-batch-size}.
 */
@ApplicationScoped
public class QdrantGrpcUpserter {

    private static final Logger LOG = Logger.getLogger(QdrantGrpcUpserter.class);

    @ConfigProperty(name = "ingest.qdrant.url")
    String restUrl;

    /**
     * {@code auto} = the host of {@code ingest.qdrant.url}. (Not an empty
     * default: SmallRye Config treats an empty string as "no value" and
     * refuses to start — that took the ingest service down for 10 minutes on
     * 2026-09-18.)
     */
    @ConfigProperty(name = "ingest.qdrant.grpc-host", defaultValue = "auto")
    String grpcHost;

    @ConfigProperty(name = "ingest.qdrant.grpc-port", defaultValue = "6334")
    int grpcPort;

    @ConfigProperty(name = "ingest.qdrant.api-key", defaultValue = "")
    String apiKey;

    @ConfigProperty(name = "ingest.qdrant.request-timeout-seconds", defaultValue = "120")
    long requestTimeoutSeconds;

    private volatile io.qdrant.client.QdrantClient client;

    /** Lazily built: nothing connects until the first upsert. */
    private io.qdrant.client.QdrantClient client() {
        io.qdrant.client.QdrantClient c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    String host = (grpcHost == null || grpcHost.isBlank() || "auto".equalsIgnoreCase(grpcHost))
                            ? URI.create(restUrl).getHost() : grpcHost;
                    QdrantGrpcClient.Builder b = QdrantGrpcClient.newBuilder(host, grpcPort, false);
                    if (apiKey != null && !apiKey.isBlank()) {
                        b.withApiKey(apiKey);
                    }
                    c = new io.qdrant.client.QdrantClient(b.build());
                    client = c;
                    LOG.infof("Qdrant gRPC upserter connected to %s:%d", host, grpcPort);
                }
            }
        }
        return c;
    }

    @PreDestroy
    void close() {
        io.qdrant.client.QdrantClient c = client;
        if (c != null) {
            c.close();
        }
    }

    /** Upsert with {@code wait=true} semantics: returns once the write is applied. */
    public void upsert(String collection, List<QdrantClient.MultiVectorPoint> points) {
        if (points == null || points.isEmpty()) {
            return;
        }
        List<PointStruct> structs = new ArrayList<>(points.size());
        for (QdrantClient.MultiVectorPoint p : points) {
            structs.add(toPointStruct(p));
        }
        UpsertPoints req = UpsertPoints.newBuilder()
                .setCollectionName(collection)
                .addAllPoints(structs)
                .setWait(true)
                .build();
        try {
            client().upsertAsync(req).get(requestTimeoutSeconds, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IngestException("Qdrant gRPC upsert into '" + collection + "' failed: "
                    + cause.getClass().getSimpleName() + ": " + cause.getMessage(), cause);
        } catch (TimeoutException e) {
            throw new IngestException("Qdrant gRPC upsert into '" + collection + "' timed out after "
                    + requestTimeoutSeconds + " s", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IngestException("Interrupted during Qdrant gRPC upsert into '" + collection + "'", e);
        }
    }

    // ---- conversion (package-private for tests) ------------------------------

    public static PointStruct toPointStruct(QdrantClient.MultiVectorPoint p) {
        NamedVectors.Builder named = NamedVectors.newBuilder();
        if (p.vectors() != null) {
            for (Map.Entry<String, float[][]> e : p.vectors().entrySet()) {
                named.putVectors(e.getKey(), multiVector(e.getValue()));
            }
        }
        PointStruct.Builder b = PointStruct.newBuilder()
                .setId(pointId(p.id()))
                .setVectors(Vectors.newBuilder().setVectors(named.build()).build());
        if (p.payload() != null) {
            for (Map.Entry<String, Object> e : p.payload().entrySet()) {
                b.putPayload(e.getKey(), toValue(e.getValue()));
            }
        }
        return b.build();
    }

    public static PointId pointId(String id) {
        try {
            return PointId.newBuilder().setUuid(UUID.fromString(id).toString()).build();
        } catch (IllegalArgumentException notUuid) {
            return PointId.newBuilder().setNum(Long.parseUnsignedLong(id)).build();
        }
    }

    /** A multivector: rows flattened, {@code vectors_count} = number of rows. */
    public static Vector multiVector(float[][] rows) {
        Vector.Builder v = Vector.newBuilder();
        if (rows == null || rows.length == 0) {
            return v.setVectorsCount(0).build();
        }
        for (float[] row : rows) {
            for (float f : row) {
                v.addData(f);
            }
        }
        return v.setVectorsCount(rows.length).build();
    }

    public static Value toValue(Object o) {
        if (o == null) {
            return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
        }
        if (o instanceof String s) {
            return Value.newBuilder().setStringValue(s).build();
        }
        if (o instanceof Boolean b) {
            return Value.newBuilder().setBoolValue(b).build();
        }
        if (o instanceof Integer || o instanceof Long || o instanceof Short || o instanceof Byte) {
            return Value.newBuilder().setIntegerValue(((Number) o).longValue()).build();
        }
        if (o instanceof Number n) {
            return Value.newBuilder().setDoubleValue(n.doubleValue()).build();
        }
        if (o instanceof Map<?, ?> m) {
            Struct.Builder sb = Struct.newBuilder();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                sb.putFields(String.valueOf(e.getKey()), toValue(e.getValue()));
            }
            return Value.newBuilder().setStructValue(sb.build()).build();
        }
        if (o instanceof Iterable<?> it) {
            ListValue.Builder lb = ListValue.newBuilder();
            for (Object x : it) {
                lb.addValues(toValue(x));
            }
            return Value.newBuilder().setListValue(lb.build()).build();
        }
        return Value.newBuilder().setStringValue(String.valueOf(o)).build();
    }
}
