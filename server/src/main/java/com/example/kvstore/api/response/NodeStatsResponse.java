package com.example.kvstore.api.response;

public record NodeStatsResponse(
        String nodeId,
        int shardIndex,
        int shardCount,
        long entryCount,
        long heapUsedBytes,
        long heapMaxBytes) {
}
