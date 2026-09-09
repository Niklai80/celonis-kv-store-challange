package com.example.kvstore.api;

import com.example.kvstore.api.response.NodeStatsResponse;
import com.example.kvstore.cluster.ClusterConfig;
import com.example.kvstore.store.StripedHashMapStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Shared by /api/v1/stats (this node) and /api/v1/cluster/stats (every node, self included). */
@Component
public class NodeStatsService {

    private final StripedHashMapStore localStore;
    private final ClusterConfig clusterConfig;
    private final String nodeId;

    public NodeStatsService(StripedHashMapStore localStore, ClusterConfig clusterConfig,
                             @Value("${HOSTNAME:local}") String nodeId) {
        this.localStore = localStore;
        this.clusterConfig = clusterConfig;
        this.nodeId = nodeId;
    }

    public NodeStatsResponse currentStats() {
        Runtime runtime = Runtime.getRuntime();
        long heapUsed = runtime.totalMemory() - runtime.freeMemory();
        return new NodeStatsResponse(nodeId, clusterConfig.shardIndex(), clusterConfig.shardCount(),
                localStore.size(), heapUsed, runtime.maxMemory());
    }
}
