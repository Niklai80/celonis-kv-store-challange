// Mirrors the records in server/src/main/java/com/example/kvstore/api/response/ exactly -
// there's no schema-generation step in this project, so field names have to be kept in sync
// by hand on both sides.

export interface KeysResponse {
  keys: string[];
  count: number;
}

export interface NodeStatsResponse {
  nodeId: string;
  shardIndex: number;
  shardCount: number;
  entryCount: number;
  heapUsedBytes: number;
  heapMaxBytes: number;
}

export interface OwnerResponse {
  key: string;
  shardIndex: number;
}
