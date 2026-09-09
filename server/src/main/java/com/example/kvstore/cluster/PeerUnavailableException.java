package com.example.kvstore.cluster;

/** A forwarded call to a peer couldn't be completed: connection failure, timeout, or the outbound semaphore for that peer was full. */
public class PeerUnavailableException extends RuntimeException {

    public final int shardIndex;

    public PeerUnavailableException(int shardIndex, String message) {
        super("shard " + shardIndex + ": " + message);
        this.shardIndex = shardIndex;
    }

    public PeerUnavailableException(int shardIndex, String message, Throwable cause) {
        super("shard " + shardIndex + ": " + message, cause);
        this.shardIndex = shardIndex;
    }
}
