package com.example.kvstore.cluster;

import java.nio.charset.StandardCharsets;

/**
 * Decides which shard owns a key: {@code owner = FNV-1a(key) mod shardCount}. Pure function of
 * {@link ClusterConfig}, no network - this is what makes it (and {@code ForwardingStore}, which
 * depends only on this) unit-testable without standing up a cluster.
 */
public class ShardRouter {

    private static final int FNV_OFFSET_BASIS = 0x811c9dc5; // 32-bit FNV-1a offset basis
    private static final int FNV_PRIME = 0x01000193; // 32-bit FNV-1a prime

    private final ClusterConfig clusterConfig;

    public ShardRouter(ClusterConfig clusterConfig) {
        this.clusterConfig = clusterConfig;
    }

    public int ownerOf(String key) {
        // hashCode() can be negative; floorMod (not %) is what gives a result in [0, shardCount)
        // instead of also allowing negative remainders.
        return Math.floorMod(fnv1a(key), clusterConfig.shardCount());
    }

    public boolean isLocal(String key) {
        return ownerOf(key) == clusterConfig.shardIndex();
    }

    public int shardCount() {
        return clusterConfig.shardCount();
    }

    public int selfShardIndex() {
        return clusterConfig.shardIndex();
    }

    /**
     * FNV-1a, 32-bit, over the key's UTF-8 bytes. Deliberately not the same function as the
     * spread-then-mask {@code String.hashCode()} used for in-segment bucket selection in
     * {@code StripedHashMapStore} - shard routing and bucket routing are independent
     * partitioning decisions, and using two different hash functions means a key's bucket
     * position inside a node can't correlate with which node owns it.
     */
    static int fnv1a(String key) {
        int hash = FNV_OFFSET_BASIS;
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xFF);
            hash *= FNV_PRIME;
        }
        return hash;
    }
}
