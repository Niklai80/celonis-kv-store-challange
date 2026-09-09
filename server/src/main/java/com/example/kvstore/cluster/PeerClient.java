package com.example.kvstore.cluster;

import com.example.kvstore.api.response.KeysResponse;
import com.example.kvstore.api.response.NodeStatsResponse;
import com.example.kvstore.store.StoreResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Talks to peer pods over plain JDK {@link HttpClient} - no third-party HTTP client, and
 * nothing more exotic than what the challenge asks for. Every outbound call carries
 * {@code X-KV-Forwarded: true} so the receiving node knows not to forward it again (loop
 * prevention lives on the receiving controller, which returns 421 if it isn't actually the
 * owner of a key sent with that header).
 *
 * <p>Each peer gets its own {@link Semaphore}. Virtual threads make "just block on the call"
 * cheap for the calling thread, which is exactly the problem: nothing stops thousands of
 * concurrent requests from piling up against one slow or dead peer the way a fixed platform
 * thread pool used to (by accident, via pool exhaustion). The semaphore makes that backpressure
 * explicit instead of relying on a side effect we no longer have.
 */
public class PeerClient {

    public static final String FORWARDED_HEADER = "X-KV-Forwarded";

    private final ClusterConfig clusterConfig;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final Duration acquireTimeout;
    private final Semaphore[] inFlightPerShard;
    private final ObjectMapper objectMapper;

    public PeerClient(ClusterConfig clusterConfig, Duration connectTimeout, Duration requestTimeout,
                       Duration acquireTimeout, int maxInFlightPerPeer, ObjectMapper objectMapper) {
        this.clusterConfig = clusterConfig;
        this.httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        this.requestTimeout = requestTimeout;
        this.acquireTimeout = acquireTimeout;
        this.objectMapper = objectMapper;
        this.inFlightPerShard = new Semaphore[clusterConfig.shardCount()];
        for (int i = 0; i < inFlightPerShard.length; i++) {
            inFlightPerShard[i] = new Semaphore(maxInFlightPerPeer);
        }
    }

    public StoreResult put(int shardIndex, String key, String value) {
        HttpRequest request = requestBuilder(shardIndex, "/api/v1/keys?key=" + encode(key))
                .header("Content-Type", "text/plain; charset=UTF-8")
                .PUT(HttpRequest.BodyPublishers.ofString(value, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(shardIndex, request);
        return switch (response.statusCode()) {
            case 201 -> new StoreResult.Created();
            case 200 -> new StoreResult.Updated();
            case 507 -> new StoreResult.CapacityExceeded(response.body());
            default -> throw unexpected(shardIndex, response);
        };
    }

    public StoreResult get(int shardIndex, String key) {
        HttpRequest request = requestBuilder(shardIndex, "/api/v1/keys/lookup?key=" + encode(key)).GET().build();
        HttpResponse<String> response = send(shardIndex, request);
        return switch (response.statusCode()) {
            case 200 -> new StoreResult.Found(response.body());
            case 404 -> new StoreResult.NotFound();
            default -> throw unexpected(shardIndex, response);
        };
    }

    public StoreResult delete(int shardIndex, String key) {
        HttpRequest request = requestBuilder(shardIndex, "/api/v1/keys?key=" + encode(key)).DELETE().build();
        HttpResponse<String> response = send(shardIndex, request);
        return switch (response.statusCode()) {
            case 204 -> new StoreResult.Deleted();
            case 404 -> new StoreResult.NotFound();
            default -> throw unexpected(shardIndex, response);
        };
    }

    /** Fetches shardIndex's own keys - the peer must answer locally only; the forwarded header on this call guarantees it does. */
    public List<String> listKeysLocal(int shardIndex, int limit) {
        HttpRequest request = requestBuilder(shardIndex, "/api/v1/keys?limit=" + limit).GET().build();
        HttpResponse<String> response = send(shardIndex, request);
        if (response.statusCode() != 200) {
            throw unexpected(shardIndex, response);
        }
        return readJson(shardIndex, response, KeysResponse.class).keys();
    }

    public NodeStatsResponse fetchStats(int shardIndex) {
        HttpRequest request = requestBuilder(shardIndex, "/api/v1/stats").GET().build();
        HttpResponse<String> response = send(shardIndex, request);
        if (response.statusCode() != 200) {
            throw unexpected(shardIndex, response);
        }
        return readJson(shardIndex, response, NodeStatsResponse.class);
    }

    private HttpRequest.Builder requestBuilder(int shardIndex, String pathAndQuery) {
        URI uri = clusterConfig.peerBaseUri(shardIndex).resolve(pathAndQuery);
        return HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header(FORWARDED_HEADER, "true");
    }

    private HttpResponse<String> send(int shardIndex, HttpRequest request) {
        Semaphore semaphore = inFlightPerShard[shardIndex];
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(acquireTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PeerUnavailableException(shardIndex, "interrupted while waiting for outbound capacity", e);
        }
        if (!acquired) {
            throw new PeerUnavailableException(shardIndex, "too many in-flight requests to this peer already");
        }
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new PeerUnavailableException(shardIndex, "request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PeerUnavailableException(shardIndex, "interrupted while calling peer", e);
        } finally {
            semaphore.release();
        }
    }

    private <T> T readJson(int shardIndex, HttpResponse<String> response, Class<T> type) {
        try {
            return objectMapper.readValue(response.body(), type);
        } catch (IOException e) {
            throw new PeerUnavailableException(shardIndex, "malformed response body", e);
        }
    }

    private static PeerUnavailableException unexpected(int shardIndex, HttpResponse<String> response) {
        return new PeerUnavailableException(shardIndex, "unexpected status " + response.statusCode() + " from peer");
    }

    private static String encode(String s) {
        // URLEncoder is form-encoding (space -> '+'), so swap it for the '%20' a URI query
        // component actually requires.
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
