package com.example.kvstore.cluster;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShardRouterTest {

    @Test
    void ownerOfIsAlwaysInRangeAndDeterministic() {
        ClusterConfig config = new ClusterConfig(4, "kv-store-2", "kv-store", "kv-store-headless", "default", 8080);
        ShardRouter router = new ShardRouter(config);

        for (int i = 0; i < 1000; i++) {
            String key = "key-" + i;
            int owner = router.ownerOf(key);
            assertThat(owner).isBetween(0, 3);
            assertThat(router.ownerOf(key)).isEqualTo(owner); // deterministic, not just in-range
        }
    }

    @Test
    void isLocalMatchesOwnerOfAgainstThisNodesShardIndex() {
        ClusterConfig config = new ClusterConfig(4, "kv-store-2", "kv-store", "kv-store-headless", "default", 8080);
        ShardRouter router = new ShardRouter(config);
        assertThat(router.selfShardIndex()).isEqualTo(2);

        for (int i = 0; i < 200; i++) {
            String key = "key-" + i;
            assertThat(router.isLocal(key)).isEqualTo(router.ownerOf(key) == 2);
        }
    }

    @Test
    void singleShardClusterOwnsEveryKeyLocally() {
        ClusterConfig config = new ClusterConfig(1, "irrelevant", "kv-store", "kv-store-headless", "default", 8080);
        ShardRouter router = new ShardRouter(config);

        assertThat(router.ownerOf("anything")).isZero();
        assertThat(router.isLocal("anything")).isTrue();
    }

    @Test
    void distributesKeysAcrossAllShardsRatherThanCollapsingToOne() {
        ClusterConfig config = new ClusterConfig(8, "kv-store-0", "kv-store", "kv-store-headless", "default", 8080);
        ShardRouter router = new ShardRouter(config);

        boolean[] seen = new boolean[8];
        for (int i = 0; i < 5000; i++) {
            seen[router.ownerOf("key-" + i)] = true;
        }
        for (boolean shardWasUsed : seen) {
            assertThat(shardWasUsed).isTrue();
        }
    }
}
