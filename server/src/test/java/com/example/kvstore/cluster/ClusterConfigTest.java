package com.example.kvstore.cluster;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClusterConfigTest {

    @Test
    void parsesOrdinalFromStatefulSetStyleHostname() {
        assertThat(ClusterConfig.parseOrdinal("kv-store-0")).isZero();
        assertThat(ClusterConfig.parseOrdinal("kv-store-11")).isEqualTo(11);
    }

    @Test
    void rejectsHostnameWithNoOrdinalSuffix() {
        assertThatThrownBy(() -> ClusterConfig.parseOrdinal("kv-store"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ClusterConfig.parseOrdinal("some-random-host"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void singleShardModeNeverParsesTheHostname() {
        // Must not throw even though "not-a-statefulset-hostname" has no numeric ordinal -
        // this is what lets the app run locally (mvn spring-boot:run, a laptop hostname) without
        // looking anything like a Kubernetes pod name.
        ClusterConfig config = new ClusterConfig(1, "not-a-statefulset-hostname",
                "kv-store", "kv-store-headless", "default", 8080);
        assertThat(config.shardIndex()).isZero();
    }

    @Test
    void buildsPeerHostnameFromStatefulSetOrdinalConvention() {
        ClusterConfig config = new ClusterConfig(3, "kv-store-1", "kv-store", "kv-store-headless", "prod", 8080);
        assertThat(config.peerHost(2)).isEqualTo("kv-store-2.kv-store-headless.prod.svc.cluster.local");
    }

    @Test
    void validateIsANoOpInSingleShardMode() {
        ClusterConfig config = new ClusterConfig(1, "whatever", "kv-store", "kv-store-headless", "default", 8080);
        // Would hang/fail on DNS resolution if it actually tried - single-shard mode must skip that entirely.
        config.validate(1, Duration.ofMillis(1));
    }

    @Test
    void validateFailsFastWhenPeerDnsNeverResolves() {
        // "*.svc.cluster.local" only resolves inside a real Kubernetes cluster, so this
        // exercises the real fail-fast path from any machine running the test suite.
        ClusterConfig config = new ClusterConfig(2, "kv-store-0", "kv-store", "kv-store-headless", "default", 8080);

        assertThatThrownBy(() -> config.validate(2, Duration.ofMillis(10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHARD_COUNT=2");
    }
}
