package com.example.kvstore.store;

/**
 * Bounds enforced by {@link StripedHashMapStore}. Without these, an unbounded PUT loop
 * (accidental or malicious) eventually OOM-kills the pod - there is no eviction in this
 * design (see README non-goals), so admission control is the only backstop.
 */
public record StoreLimits(int maxKeyLength, int maxValueLength, int maxEntriesPerNode) {

    public static StoreLimits defaults() {
        return new StoreLimits(512, 1_048_576, 1_000_000);
    }

    public StoreLimits {
        if (maxKeyLength <= 0 || maxValueLength <= 0 || maxEntriesPerNode <= 0) {
            throw new IllegalArgumentException("store limits must be positive");
        }
    }
}
