package com.example.kvstore.bench;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Standalone HTTP load generator - one virtual thread per concurrent "client", each looping
 * PUT/GET requests against a running node (or the cluster, if pointed at any node - forwarding
 * is transparent to the caller). Reports throughput and p50/p95/p99 latency.
 *
 * <p>Run: {@code mvn -q compile && java -cp target/classes com.example.kvstore.bench.LoadGenerator
 * [baseUrl] [concurrency] [durationSeconds]}
 * <p>Defaults: http://localhost:8080, 64 concurrent clients, 10 seconds.
 *
 * <p>Virtual threads here aren't the point being benchmarked (they're what the server uses on
 * the forwarding path) - they're just a convenient way to run thousands of concurrent blocking
 * HTTP calls from this client without needing a reactive HTTP client or a huge platform-thread
 * pool.
 */
public final class LoadGenerator {

    private static final int MAX_SAMPLES = 2_000_000;

    public static void main(String[] args) throws InterruptedException {
        String baseUrl = args.length > 0 ? args[0] : "http://localhost:8080";
        int concurrency = args.length > 1 ? Integer.parseInt(args[1]) : 64;
        int durationSeconds = args.length > 2 ? Integer.parseInt(args[2]) : 10;

        System.out.printf("target=%s concurrency=%d duration=%ds%n", baseUrl, concurrency, durationSeconds);

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        AtomicLong completed = new AtomicLong();
        AtomicLong errors = new AtomicLong();
        AtomicLong sampleIndex = new AtomicLong();
        AtomicLongArray latenciesNanos = new AtomicLongArray(MAX_SAMPLES);

        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch startGate = new CountDownLatch(1);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(durationSeconds);

        for (int i = 0; i < concurrency; i++) {
            int workerId = i;
            pool.submit(() -> {
                try {
                    startGate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                long counter = 0;
                while (System.nanoTime() < deadline) {
                    String key = "load-" + workerId + "-" + (counter % 1000);
                    boolean isRead = counter % 5 != 0; // 80% GET / 20% PUT, matching MapBenchmark
                    long start = System.nanoTime();
                    boolean ok = isRead ? doGet(client, baseUrl, key) : doPut(client, baseUrl, key);
                    long elapsed = System.nanoTime() - start;
                    if (ok) {
                        completed.incrementAndGet();
                        long idx = sampleIndex.getAndIncrement();
                        if (idx < MAX_SAMPLES) {
                            latenciesNanos.set((int) idx, elapsed);
                        }
                    } else {
                        errors.incrementAndGet();
                    }
                    counter++;
                }
            });
        }

        startGate.countDown();
        pool.shutdown();
        pool.awaitTermination(durationSeconds + 30, TimeUnit.SECONDS);

        int sampleCount = (int) Math.min(sampleIndex.get(), MAX_SAMPLES);
        long[] samples = new long[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            samples[i] = latenciesNanos.get(i);
        }
        Arrays.sort(samples);

        double throughput = completed.get() / (double) durationSeconds;
        System.out.printf("completed=%d errors=%d throughput=%.0f ops/sec%n", completed.get(), errors.get(), throughput);
        System.out.printf("p50=%.2fms p95=%.2fms p99=%.2fms max=%.2fms%n",
                percentileMillis(samples, 0.50), percentileMillis(samples, 0.95),
                percentileMillis(samples, 0.99), samples.length == 0 ? 0 : samples[samples.length - 1] / 1_000_000.0);
    }

    private static double percentileMillis(long[] sortedNanos, double p) {
        if (sortedNanos.length == 0) {
            return 0;
        }
        int idx = (int) Math.min(sortedNanos.length - 1, Math.floor(p * sortedNanos.length));
        return sortedNanos[idx] / 1_000_000.0;
    }

    private static boolean doPut(HttpClient client, String baseUrl, String key) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/keys?key=" + key))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "text/plain")
                    .PUT(HttpRequest.BodyPublishers.ofString("v", StandardCharsets.UTF_8))
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200 || response.statusCode() == 201;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean doGet(HttpClient client, String baseUrl, String key) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/keys/lookup?key=" + key))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200 || response.statusCode() == 404;
        } catch (Exception e) {
            return false;
        }
    }
}
