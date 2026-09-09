package com.example.kvstore.bench;

import com.example.kvstore.store.StoreLimits;
import com.example.kvstore.store.StripedHashMapStore;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hand-rolled microbenchmark (deliberately no JMH - see README "how this differs from
 * ConcurrentHashMap"). Compares StripedHashMapStore against java.util.concurrent.ConcurrentHashMap
 * and Collections.synchronizedMap(HashMap) under an 80% read / 20% write mixed workload, at
 * several thread counts, on a fixed key space (so both hits and lock contention are realistic).
 *
 * <p>Run: {@code mvn -q compile && java -cp target/classes com.example.kvstore.bench.MapBenchmark}
 *
 * <p>This is a rough, single-run, single-machine microbenchmark, not a rigorous JMH study - no
 * forked JVMs, no dead-code-elimination guards beyond consuming results into a blackhole field,
 * no confidence intervals. It's enough to show the shape of the difference, not to defend a
 * number to the third decimal place.
 */
public final class MapBenchmark {

    private static final int KEY_SPACE = 10_000;
    private static final double READ_FRACTION = 0.8;
    private static final int WARMUP_MILLIS = 1_500;
    private static final int MEASURE_MILLIS = 2_000;
    private static final int[] THREAD_COUNTS = {1, 4, 8, 16, 32};

    // Volatile so the JIT can't prove nothing ever reads the values written into the maps and
    // optimize the writes away.
    private static volatile String blackhole;

    public static void main(String[] args) throws InterruptedException {
        System.out.println("Mixed workload: " + (int) (READ_FRACTION * 100) + "% GET / "
                + (int) ((1 - READ_FRACTION) * 100) + "% PUT, key space=" + KEY_SPACE);
        System.out.printf("%-12s %8s %15s%n", "impl", "threads", "ops/sec");

        for (int threads : THREAD_COUNTS) {
            runOne("StripedHashMapStore", threads, new StripedStoreTarget());
            runOne("ConcurrentHashMap", threads, new MapTarget(new ConcurrentHashMap<>()));
            runOne("synchronizedMap", threads, new MapTarget(Collections.synchronizedMap(new HashMap<>())));
        }
    }

    private static void runOne(String name, int threadCount, BenchTarget target) throws InterruptedException {
        seed(target);
        run(target, threadCount, WARMUP_MILLIS); // warm up the JIT before measuring
        long ops = run(target, threadCount, MEASURE_MILLIS);
        double perSecond = ops / (MEASURE_MILLIS / 1000.0);
        System.out.printf("%-12s %8d %15.0f%n", name, threadCount, perSecond);
    }

    private static void seed(BenchTarget target) {
        for (int i = 0; i < KEY_SPACE; i++) {
            target.put("key-" + i, "value-" + i);
        }
    }

    private static long run(BenchTarget target, int threadCount, long durationMillis) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicLong totalOps = new AtomicLong();
        AtomicLong stopAt = new AtomicLong(Long.MAX_VALUE);

        for (int t = 0; t < threadCount; t++) {
            long seed = t;
            pool.submit(() -> {
                Random random = new Random(seed);
                try {
                    startGate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                long ops = 0;
                while (System.nanoTime() < stopAt.get()) {
                    String key = "key-" + random.nextInt(KEY_SPACE);
                    if (random.nextDouble() < READ_FRACTION) {
                        blackhole = target.get(key);
                    } else {
                        target.put(key, "v" + random.nextLong());
                    }
                    ops++;
                }
                totalOps.addAndGet(ops);
            });
        }

        stopAt.set(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMillis));
        startGate.countDown();
        pool.shutdown();
        pool.awaitTermination(durationMillis + 5_000, TimeUnit.MILLISECONDS);
        return totalOps.get();
    }

    private interface BenchTarget {
        void put(String key, String value);

        String get(String key);
    }

    private static final class StripedStoreTarget implements BenchTarget {
        private final StripedHashMapStore store = new StripedHashMapStore(16, StoreLimits.defaults());

        @Override
        public void put(String key, String value) {
            store.put(key, value);
        }

        @Override
        public String get(String key) {
            return switch (store.get(key)) {
                case com.example.kvstore.store.StoreResult.Found found -> found.value();
                default -> null;
            };
        }
    }

    private static final class MapTarget implements BenchTarget {
        private final Map<String, String> map;

        private MapTarget(Map<String, String> map) {
            this.map = map;
        }

        @Override
        public void put(String key, String value) {
            map.put(key, value);
        }

        @Override
        public String get(String key) {
            return map.get(key);
        }
    }
}
