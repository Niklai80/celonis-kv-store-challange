package com.example.kvstore.store;

/**
 * Outcome of a store operation. Sealed so the controller's pattern match is exhaustive:
 * the compiler forces us to handle every case, so adding a new result later can't silently
 * fall through to a wrong HTTP status.
 */
public sealed interface StoreResult {

    record Created() implements StoreResult {}

    record Updated() implements StoreResult {}

    record Found(String value) implements StoreResult {}

    record NotFound() implements StoreResult {}

    record Deleted() implements StoreResult {}

    /** A configured bound (max key length, max value length, or max entries per node) was hit. */
    record CapacityExceeded(String reason) implements StoreResult {}
}
