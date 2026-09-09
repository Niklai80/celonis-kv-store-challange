package com.example.kvstore.cluster;

import com.example.kvstore.store.KeyValueStore;
import com.example.kvstore.store.StoreResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Decorates a local {@link KeyValueStore} with cluster awareness. For each key, {@link ShardRouter}
 * decides local-or-remote; a local key goes straight to the wrapped store, a remote one is
 * proxied through {@link PeerClient}. The controller depends only on {@link KeyValueStore}, so
 * it cannot tell (and doesn't need to know) whether a given call stayed on this node or crossed
 * the network - that's the point of this being a decorator instead of routing logic living in
 * the controller itself.
 */
public final class ForwardingStore implements KeyValueStore {

    private final KeyValueStore local;
    private final ShardRouter router;
    private final PeerClient peerClient;

    public ForwardingStore(KeyValueStore local, ShardRouter router, PeerClient peerClient) {
        this.local = local;
        this.router = router;
        this.peerClient = peerClient;
    }

    @Override
    public StoreResult put(String key, String value) {
        return router.isLocal(key) ? local.put(key, value) : peerClient.put(router.ownerOf(key), key, value);
    }

    @Override
    public StoreResult get(String key) {
        return router.isLocal(key) ? local.get(key) : peerClient.get(router.ownerOf(key), key);
    }

    @Override
    public StoreResult delete(String key) {
        return router.isLocal(key) ? local.delete(key) : peerClient.delete(router.ownerOf(key), key);
    }

    @Override
    public long size() {
        // This node's own entry count, not a cluster-wide total - consistent with /api/v1/stats
        // being a per-node endpoint. A cluster-wide total is /api/v1/cluster/stats summing every
        // node's own reported size, not something this store computes internally.
        return local.size();
    }

    @Override
    public List<String> keys(int limit) {
        List<String> result = new ArrayList<>(local.keys(limit));
        for (int shard = 0; shard < router.shardCount() && result.size() < limit; shard++) {
            if (shard == router.selfShardIndex()) {
                continue;
            }
            result.addAll(peerClient.listKeysLocal(shard, limit - result.size()));
        }
        return result;
    }
}
