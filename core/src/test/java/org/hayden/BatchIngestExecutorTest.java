package org.hayden;

import org.hayden.ingest.BatchIngestExecutor;
import org.hayden.ingest.DirectoryFileOutcome;
import org.hayden.ingest.IngestException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The fan-out contract both bulk paths (directory scan, upload) rely on:
 * submit-order outcomes, bounded parallelism, and error capture staying in
 * the per-item function. Lifted from the directory path's coverage when the
 * executor was extracted.
 */
class BatchIngestExecutorTest {

    @Test
    void outcomes_preserveSubmitOrder_despiteRacyCompletion() {
        // Every item blocks until all three are in flight — completion order
        // is then scheduler-random, but outcomes must come back in order.
        CyclicBarrier allInFlight = new CyclicBarrier(3);
        List<DirectoryFileOutcome> out = BatchIngestExecutor.ingestAll(
                "test-batch-", 3, List.of("a", "b", "c"),
                name -> {
                    try {
                        allInFlight.await(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IngestException("not concurrent: " + e, e);
                    }
                    return outcome(name, "completed");
                });

        assertThat(out).extracting(DirectoryFileOutcome::filename)
                .containsExactly("a", "b", "c");
        assertThat(out).extracting(DirectoryFileOutcome::status)
                .containsOnly("completed");
    }

    @Test
    void parallelism_isBounded() {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxSeen = new AtomicInteger();
        BatchIngestExecutor.ingestAll("test-batch-", 2,
                List.of("a", "b", "c", "d", "e", "f"),
                name -> {
                    int now = inFlight.incrementAndGet();
                    maxSeen.accumulateAndGet(now, Math::max);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ignored) {
                    } finally {
                        inFlight.decrementAndGet();
                    }
                    return outcome(name, "completed");
                });

        assertThat(maxSeen.get()).isLessThanOrEqualTo(2);
    }

    @Test
    void errorOutcomes_flowThrough_withoutAbortingTheBatch() {
        List<DirectoryFileOutcome> out = BatchIngestExecutor.ingestAll(
                "test-batch-", 2, List.of("ok", "bad", "ok2"),
                name -> outcome(name, name.startsWith("bad") ? "error" : "completed"));

        assertThat(out).extracting(DirectoryFileOutcome::status)
                .containsExactly("completed", "error", "completed");
    }

    @Test
    void parallelismBelowOne_throws() {
        assertThatThrownBy(() -> BatchIngestExecutor.ingestAll(
                "test-batch-", 0, List.of("a"), n -> outcome(n, "completed")))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("parallelism");
    }

    @Test
    void emptyInput_returnsEmptyWithoutSpawningThreads() {
        assertThat(BatchIngestExecutor.ingestAll("test-batch-", 4,
                List.<String>of(), n -> outcome(n, "completed"))).isEmpty();
    }

    private static DirectoryFileOutcome outcome(String name, String status) {
        return new DirectoryFileOutcome("/x/" + name, name, "id-" + name,
                status, null, null, null, null);
    }
}
