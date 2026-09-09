package com.example.kvstore.config;

import com.example.kvstore.cluster.ClusterConfig;
import com.example.kvstore.cluster.ForwardingStore;
import com.example.kvstore.cluster.PeerClient;
import com.example.kvstore.cluster.ShardRouter;
import com.example.kvstore.store.KeyValueStore;
import com.example.kvstore.store.StripedHashMapStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * Wires the sharding/forwarding layer on top of the local store from {@link StoreConfig}.
 * {@code cluster/} itself has no Spring annotations - all of that lives here.
 */
@Configuration
public class ClusterBeansConfig {

    @Bean
    public ClusterConfig clusterConfig(
            @Value("${SHARD_COUNT:1}") int shardCount,
            @Value("${HOSTNAME:local}") String podHostname,
            @Value("${kvstore.cluster.statefulset-name:kv-store}") String statefulSetName,
            @Value("${kvstore.cluster.headless-service:kv-store-headless}") String headlessServiceName,
            @Value("${NAMESPACE:default}") String namespace,
            @Value("${kvstore.cluster.peer-port:8080}") int peerPort) {
        return new ClusterConfig(shardCount, podHostname, statefulSetName, headlessServiceName, namespace, peerPort);
    }

    @Bean
    public ShardRouter shardRouter(ClusterConfig clusterConfig) {
        return new ShardRouter(clusterConfig);
    }

    @Bean
    public PeerClient peerClient(
            ClusterConfig clusterConfig,
            ObjectMapper objectMapper,
            @Value("${kvstore.cluster.forward.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${kvstore.cluster.forward.request-timeout-ms:5000}") long requestTimeoutMs,
            @Value("${kvstore.cluster.forward.acquire-timeout-ms:2000}") long acquireTimeoutMs,
            @Value("${kvstore.cluster.forward.max-in-flight-per-peer:64}") int maxInFlightPerPeer) {
        return new PeerClient(clusterConfig, Duration.ofMillis(connectTimeoutMs), Duration.ofMillis(requestTimeoutMs),
                Duration.ofMillis(acquireTimeoutMs), maxInFlightPerPeer, objectMapper);
    }

    /**
     * What the controller actually injects for cluster-wide operations. Degrades gracefully to
     * "always local" when SHARD_COUNT=1 (ShardRouter.isLocal is then unconditionally true), so
     * there's no separate single-node code path to keep in sync with this one.
     */
    @Bean
    @Primary
    public KeyValueStore keyValueStore(StripedHashMapStore localStore, ShardRouter shardRouter, PeerClient peerClient) {
        return new ForwardingStore(localStore, shardRouter, peerClient);
    }

    /**
     * Runs during application startup, before Spring Boot reports the app as ready - so
     * throwing here keeps the pod out of rotation entirely instead of serving traffic on a
     * cluster view it can't validate. Disabled only for tests that don't want to depend on real
     * cluster DNS.
     */
    @Bean
    @ConditionalOnProperty(name = "kvstore.cluster.validation.enabled", havingValue = "true", matchIfMissing = true)
    public ApplicationRunner clusterValidationRunner(
            ClusterConfig clusterConfig,
            @Value("${kvstore.cluster.validation.max-attempts:30}") int maxAttempts,
            @Value("${kvstore.cluster.validation.retry-interval-ms:1000}") long retryIntervalMs) {
        return args -> clusterConfig.validate(maxAttempts, Duration.ofMillis(retryIntervalMs));
    }
}
