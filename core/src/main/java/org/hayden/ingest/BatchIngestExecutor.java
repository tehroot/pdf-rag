package org.hayden.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * Bounded-parallel fan-out shared by the two bulk ingest paths (directory scan
 * and HTTP upload). Runs {@code ingestOne} for each item on a fixed pool of at
 * most {@code parallelism} threads and returns the outcomes in submit order,
 * regardless of completion order.
 *
 * <p>Per-item failure handling belongs to the caller: {@code ingestOne} is
 * expected to capture its own RuntimeExceptions as {@code "error"} outcomes
 * (so one bad file never aborts the batch). Anything that still surfaces here
 * is unexpected (e.g. an Error) and fails the whole batch.
 */
public final class BatchIngestExecutor {

    private BatchIngestExecutor() {
    }

    public static <T> List<DirectoryFileOutcome> ingestAll(
            String threadPrefix, int parallelism, List<T> items,
            Function<T, DirectoryFileOutcome> ingestOne) {
        if (parallelism < 1) {
            throw new IngestException("batch parallelism must be >= 1 (got " + parallelism + ")");
        }
        if (items.isEmpty()) {
            return List.of();
        }
        int threads = Math.min(parallelism, items.size());
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r);
            t.setName(threadPrefix + t.threadId());
            return t;
        });
        try {
            List<Future<DirectoryFileOutcome>> futures = new ArrayList<>(items.size());
            for (T item : items) {
                futures.add(pool.submit(() -> ingestOne.apply(item)));
            }
            List<DirectoryFileOutcome> outcomes = new ArrayList<>(items.size());
            for (Future<DirectoryFileOutcome> future : futures) {
                outcomes.add(future.get());
            }
            return outcomes;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IngestException("Batch ingest interrupted", e);
        } catch (ExecutionException e) {
            // ingestOne captures all RuntimeExceptions as outcomes; anything
            // surfacing here is unexpected (e.g. an Error).
            throw new IngestException("Batch ingest worker failed: "
                    + e.getCause().getMessage(), e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }
}
