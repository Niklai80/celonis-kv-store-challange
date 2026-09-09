package com.example.kvstore.store;

import java.util.List;

/**
 * The store contract. Both the local {@link StripedHashMapStore} and the
 * cluster-aware {@code ForwardingStore} implement this, so the REST controller
 * never knows (or cares) whether a given call stays local or crosses the network.
 */
public interface KeyValueStore {

    StoreResult put(String key, String value);

    StoreResult get(String key);

    StoreResult delete(String key);

    /**
     * Number of entries currently held. Under concurrent mutation this is a
     * point-in-time estimate, not a linearizable snapshot - see StripedHashMapStore.
     */
    long size();

    /**
     * Up to {@code limit} keys held by this store. On {@code ForwardingStore} this fans out
     * to every shard (scatter-gather); on the local store it's just this node's keys.
     */
    List<String> keys(int limit);
}
