package com.example.kvstore.api;

import com.example.kvstore.api.response.NodeStatsResponse;
import com.example.kvstore.cluster.ClusterConfig;
import com.example.kvstore.cluster.PeerClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/** Fans /api/v1/stats out across every node, self included, using the same PeerClient the store forwarding path uses. */
@RestController
public class ClusterController {

    private final ClusterConfig clusterConfig;
    private final PeerClient peerClient;
    private final NodeStatsService nodeStatsService;

    public ClusterController(ClusterConfig clusterConfig, PeerClient peerClient, NodeStatsService nodeStatsService) {
        this.clusterConfig = clusterConfig;
        this.peerClient = peerClient;
        this.nodeStatsService = nodeStatsService;
    }

    @GetMapping("/api/v1/cluster/stats")
    public List<NodeStatsResponse> clusterStats() {
        List<NodeStatsResponse> all = new ArrayList<>();
        for (int shard = 0; shard < clusterConfig.shardCount(); shard++) {
            all.add(shard == clusterConfig.shardIndex() ? nodeStatsService.currentStats() : peerClient.fetchStats(shard));
        }
        return all;
    }
}
