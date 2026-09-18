package org.hayden;

import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.Vector;
import org.hayden.backend.qdrant.QdrantClient;
import org.hayden.backend.qdrant.QdrantGrpcUpserter;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The REST -> protobuf conversion must carry ids, multivectors and payload types faithfully. */
class QdrantGrpcUpserterTest {

    @Test
    void multiVector_flattensRowsAndCountsThem() {
        Vector v = QdrantGrpcUpserter.multiVector(new float[][]{{0.1f, 0.2f, 0.3f}, {0.4f, 0.5f, 0.6f}});
        assertThat(v.getVectorsCount()).isEqualTo(2);
        assertThat(v.getDataList()).containsExactly(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f);
    }

    @Test
    void multiVector_emptyIsZeroRows() {
        Vector v = QdrantGrpcUpserter.multiVector(new float[0][]);
        assertThat(v.getVectorsCount()).isZero();
        assertThat(v.getDataCount()).isZero();
    }

    @Test
    void pointId_uuidAndNumeric() {
        assertThat(QdrantGrpcUpserter.pointId("9973b06d-5d9a-5cdc-ab28-fc868cbcc33d").getUuid())
                .isEqualTo("9973b06d-5d9a-5cdc-ab28-fc868cbcc33d");
        assertThat(QdrantGrpcUpserter.pointId("42").getNum()).isEqualTo(42L);
    }

    @Test
    void toValue_mapsJavaTypesToQdrantJsonWithInt() {
        assertThat(QdrantGrpcUpserter.toValue("s").getStringValue()).isEqualTo("s");
        assertThat(QdrantGrpcUpserter.toValue(7).getIntegerValue()).isEqualTo(7L);
        assertThat(QdrantGrpcUpserter.toValue(7L).getIntegerValue()).isEqualTo(7L);
        assertThat(QdrantGrpcUpserter.toValue(1.5).getDoubleValue()).isEqualTo(1.5);
        assertThat(QdrantGrpcUpserter.toValue(true).getBoolValue()).isTrue();
        assertThat(QdrantGrpcUpserter.toValue(null).hasNullValue()).isTrue();
        Value list = QdrantGrpcUpserter.toValue(List.of("a", 2));
        assertThat(list.getListValue().getValuesCount()).isEqualTo(2);
        assertThat(list.getListValue().getValues(1).getIntegerValue()).isEqualTo(2L);
        Value struct = QdrantGrpcUpserter.toValue(Map.of("k", "v"));
        assertThat(struct.getStructValue().getFieldsMap().get("k").getStringValue()).isEqualTo("v");
    }

    @Test
    void toPointStruct_carriesNamedMultivectorsAndPayload() {
        Map<String, float[][]> vectors = new LinkedHashMap<>();
        vectors.put("original", new float[][]{{1f, 2f}, {3f, 4f}, {5f, 6f}});
        vectors.put("pooled_rows", new float[][]{{7f, 8f}});
        vectors.put("pooled_cols", new float[0][]);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("doc_id", "d-1");
        payload.put("page_number", 3);
        payload.put("page_image_size_bytes", 123456);
        payload.put("crawl_date", "2026-09-16");

        PointStruct ps = QdrantGrpcUpserter.toPointStruct(
                new QdrantClient.MultiVectorPoint("9973b06d-5d9a-5cdc-ab28-fc868cbcc33d", vectors, payload));

        assertThat(ps.getId().getUuid()).isEqualTo("9973b06d-5d9a-5cdc-ab28-fc868cbcc33d");
        Map<String, Vector> named = ps.getVectors().getVectors().getVectorsMap();
        assertThat(named).containsOnlyKeys("original", "pooled_rows", "pooled_cols");
        assertThat(named.get("original").getVectorsCount()).isEqualTo(3);
        assertThat(named.get("original").getDataCount()).isEqualTo(6);
        assertThat(named.get("pooled_rows").getDataList()).containsExactly(7f, 8f);
        assertThat(named.get("pooled_cols").getVectorsCount()).isZero();
        assertThat(ps.getPayloadMap().get("page_number").getIntegerValue()).isEqualTo(3L);
        assertThat(ps.getPayloadMap().get("doc_id").getStringValue()).isEqualTo("d-1");
        assertThat(ps.getPayloadMap().get("crawl_date").getStringValue()).isEqualTo("2026-09-16");
    }
}
