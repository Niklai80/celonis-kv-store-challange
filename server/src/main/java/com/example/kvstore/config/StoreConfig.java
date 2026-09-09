package com.example.kvstore.config;

import com.example.kvstore.store.StoreLimits;
import com.example.kvstore.store.StripedHashMapStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The only place the framework and the store meet. {@code store/} itself imports nothing
 * from Spring - this class is the wiring, not the store.
 */
@Configuration
public class StoreConfig {

    @Bean
    public StoreLimits storeLimits(
            @Value("${kvstore.limits.max-key-length:512}") int maxKeyLength,
            @Value("${kvstore.limits.max-value-length:1048576}") int maxValueLength,
            @Value("${kvstore.limits.max-entries-per-node:1000000}") int maxEntriesPerNode) {
        return new StoreLimits(maxKeyLength, maxValueLength, maxEntriesPerNode);
    }

    /**
     * The local, single-node store. In cluster mode this bean gets wrapped by a
     * {@code ForwardingStore} (see the {@code cluster} package) which decides per key
     * whether to serve from this instance or proxy to the owning peer; the controller
     * only ever depends on the {@code KeyValueStore} interface, never on this bean directly.
     */
    @Bean
    public StripedHashMapStore localStore(
            @Value("${kvstore.segments:16}") int segmentCount,
            StoreLimits storeLimits) {
        return new StripedHashMapStore(segmentCount, storeLimits);
    }
}
