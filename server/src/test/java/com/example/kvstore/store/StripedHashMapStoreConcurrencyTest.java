package com.example.kvstore.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the same mixed PUT/DELETE workload against StripedHashMapStore and a
 * ConcurrentHashMap "oracle" from many virtual threads, then asserts the final
 * state matches exactly. If our locking has a bug (a missed lock, a race between
 * the collision check and the insert, etc.) the two will diverge.
 */
class StripedHashMapStoreConcurrencyTest {

    @Test
    @Timeout(60)
    void mixedConcurrentOperationsMatchConcurrentHashMapOracle() throws InterruptedException {
        StripedHashMapStore store = new StripedHashMapStore(16, new StoreLimits(64, 64, 10_000_000));
        Map<String, String> oracle = new ConcurrentHashMap<>();

        int threadCount = 64;
        int opsPerThread = 2_000;
        int keySpace = 500; // deliberately small so collisions/updates/deletes on the same key are common

        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger failures = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            long seed = t;
            pool.submit(() -> {
                try {
                    start.await();
                    Random rnd = new Random(seed);
                    for (int i = 0; i < opsPerThread; i++) {
                        String key = "k" + rnd.nextInt(keySpace);
                        int op = rnd.nextInt(3);
                        switch (op) {
                            case 0 -> {
                                String value = "v" + rnd.nextInt();
                                store.put(key, value);
                                oracle.put(key, value);
                            }
                            case 1 -> {
                                store.delete(key);
                                oracle.remove(key);
                            }
                            default -> {
                                // GET against both, tolerating the inherent race: two concurrent
                                // writers to the same key mean this isn't asserted per-op, only
                                // the converged final state (below) is.
                                store.get(key);
                                oracle.get(key);
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(50, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(failures.get()).isZero();
        assertThat(store.size()).isEqualTo(oracle.size());
        for (Map.Entry<String, String> entry : oracle.entrySet()) {
            assertThat(store.get(entry.getKey())).isEqualTo(new StoreResult.Found(entry.getValue()));
        }
    }

    @Test
    @Timeout(60)
    void resizeUnderConcurrentLoadKeepsAllEntriesReachable() throws InterruptedException {
        // Small initial footprint (few segments), large key count, all threads writing
        // disjoint keys concurrently - this forces every segment through multiple resizes
        // while other threads are actively reading/writing neighboring segments.
        StripedHashMapStore store = new StripedHashMapStore(4, new StoreLimits(64, 64, 1_000_000));

        int threadCount = 32;
        int keysPerThread = 3_000;
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger failures = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            int threadId = t;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < keysPerThread; i++) {
                        String key = "t" + threadId + "-k" + i;
                        store.put(key, key + "-value");
                    }
                } catch (RuntimeException e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(done.await(50, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(failures.get()).isZero();
        assertThat(store.size()).isEqualTo((long) threadCount * keysPerThread);

        for (int t = 0; t < threadCount; t++) {
            for (int i = 0; i < keysPerThread; i++) {
                String key = "t" + t + "-k" + i;
                assertThat(store.get(key)).isEqualTo(new StoreResult.Found(key + "-value"));
            }
        }
    }

    @Test
    void keysReturnsUpToLimitAcrossSegments() {
        StripedHashMapStore store = new StripedHashMapStore(8, StoreLimits.defaults());
        for (int i = 0; i < 100; i++) {
            store.put("key-" + i, "v");
        }
        List<String> keys = store.keys(30);
        assertThat(keys).hasSize(30);
        assertThat(keys).allMatch(k -> k.startsWith("key-"));
    }
}
