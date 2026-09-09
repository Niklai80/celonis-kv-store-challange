package com.example.kvstore.cluster;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * This node's view of the cluster: how many shards there are, which one this node is, and how
 * to address every peer. Peers are StatefulSet ordinals behind a headless Service:
 * {@code kv-store-{i}.kv-store-headless.{namespace}.svc.cluster.local:8080}.
 */
public class ClusterConfig {

    private final int shardIndex;
    private final int shardCount;
    private final String statefulSetName;
    private final String headlessServiceName;
    private final String namespace;
    private final int peerPort;

    public ClusterConfig(int shardCount, String podHostname, String statefulSetName,
                          String headlessServiceName, String namespace, int peerPort) {
        if (shardCount <= 0) {
            throw new IllegalArgumentException("SHARD_COUNT must be positive, got " + shardCount);
        }
        this.shardCount = shardCount;
        // Single-node/local-dev mode never needs to parse a StatefulSet ordinal out of the
        // hostname - there's only one possible shard index. This is also what lets the whole
        // app run outside Kubernetes (docker-compose, `mvn spring-boot:run`) without a
        // StatefulSet-shaped hostname at all.
        this.shardIndex = shardCount == 1 ? 0 : parseOrdinal(podHostname);
        this.statefulSetName = statefulSetName;
        this.headlessServiceName = headlessServiceName;
        this.namespace = namespace;
        this.peerPort = peerPort;
    }

    static int parseOrdinal(String hostname) {
        int dash = hostname.lastIndexOf('-');
        if (dash < 0 || dash == hostname.length() - 1) {
            throw new IllegalStateException("SHARD_COUNT > 1 requires a StatefulSet pod hostname ending in "
                    + "'-<ordinal>' (e.g. kv-store-2), got HOSTNAME=" + hostname);
        }
        try {
            return Integer.parseInt(hostname.substring(dash + 1));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("cannot parse a shard ordinal from HOSTNAME=" + hostname, e);
        }
    }

    public int shardIndex() {
        return shardIndex;
    }

    public int shardCount() {
        return shardCount;
    }

    public String peerHost(int shardIdx) {
        return statefulSetName + "-" + shardIdx + "." + headlessServiceName + "." + namespace + ".svc.cluster.local";
    }

    public URI peerBaseUri(int shardIdx) {
        return URI.create("http://" + peerHost(shardIdx) + ":" + peerPort);
    }

    /**
     * Confirms every peer's DNS name (0..shardCount-1, including this node's own) resolves
     * before this node serves traffic. A silent SHARD_COUNT/replica-count mismatch is the worst
     * failure mode in this design: a node could compute an owner shard index that doesn't
     * correspond to any real pod, or two nodes could silently disagree about who owns what -
     * neither shows up as an error, just wrong answers. Failing fast here trades that for a
     * crash-loop, which is at least visible.
     *
     * <p>Retries tolerate ordering during StatefulSet startup: with the default
     * {@code podManagementPolicy: OrderedReady}, pod 2 isn't even created until pod 1 is Ready,
     * so pod 0 briefly can't resolve pod 2's DNS name yet. The Kubernetes manifests in this
     * project use {@code podManagementPolicy: Parallel} specifically to shrink this window, but
     * the retry loop is what makes correctness not depend on that.
     */
    public void validate(int maxAttempts, Duration retryInterval) {
        if (shardCount == 1) {
            return;
        }
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            List<String> unresolved = unresolvedPeers();
            if (unresolved.isEmpty()) {
                return;
            }
            if (attempt == maxAttempts) {
                throw new IllegalStateException("SHARD_COUNT=" + shardCount + " but could not resolve peer "
                        + "hostname(s) " + unresolved + " after " + maxAttempts + " attempts. Refusing to start: "
                        + "a silent shard-count/replica-count mismatch is worse than a crash-loop.");
            }
            sleep(retryInterval);
        }
    }

    private List<String> unresolvedPeers() {
        List<String> unresolved = new ArrayList<>();
        for (int i = 0; i < shardCount; i++) {
            String host = peerHost(i);
            try {
                InetAddress.getAllByName(host);
            } catch (UnknownHostException e) {
                unresolved.add(host);
            }
        }
        return unresolved;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for peer DNS to become resolvable", e);
        }
    }
}
