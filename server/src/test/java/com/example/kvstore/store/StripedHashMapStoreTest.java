package com.example.kvstore.store;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StripedHashMapStoreTest {

    private final StripedHashMapStore store = new StripedHashMapStore();

    @Test
    void putOnNewKeyReturnsCreated() {
        assertThat(store.put("a", "1")).isInstanceOf(StoreResult.Created.class);
        assertThat(store.get("a")).isEqualTo(new StoreResult.Found("1"));
    }

    @Test
    void putOnExistingKeyReturnsUpdatedAndReplacesValueInPlace() {
        store.put("a", "1");
        StoreResult result = store.put("a", "2");

        assertThat(result).isInstanceOf(StoreResult.Updated.class);
        assertThat(store.get("a")).isEqualTo(new StoreResult.Found("2"));
        assertThat(store.size()).isEqualTo(1); // update must not create a second entry
    }

    @Test
    void getOnMissingKeyReturnsNotFound() {
        assertThat(store.get("missing")).isInstanceOf(StoreResult.NotFound.class);
    }

    @Test
    void deleteRemovesEntryAndReturnsDeleted() {
        store.put("a", "1");

        assertThat(store.delete("a")).isInstanceOf(StoreResult.Deleted.class);
        assertThat(store.get("a")).isInstanceOf(StoreResult.NotFound.class);
        assertThat(store.size()).isZero();
    }

    @Test
    void deleteOnMissingKeyReturnsNotFound() {
        assertThat(store.delete("nope")).isInstanceOf(StoreResult.NotFound.class);
    }

    @Test
    void nullKeyIsRejectedOnAllOperations() {
        assertThrows(NullPointerException.class, () -> store.put(null, "v"));
        assertThrows(NullPointerException.class, () -> store.get(null));
        assertThrows(NullPointerException.class, () -> store.delete(null));
    }

    @Test
    void nullValueIsRejectedOnPut() {
        assertThrows(NullPointerException.class, () -> store.put("k", null));
    }

    @Test
    void handlesHashCollisionsWithinASegment() {
        // "Aa" and "BB" are the textbook java.lang.String hashCode collision pair.
        assertThat("Aa".hashCode()).isEqualTo("BB".hashCode());

        store.put("Aa", "first");
        store.put("BB", "second");

        assertThat(store.get("Aa")).isEqualTo(new StoreResult.Found("first"));
        assertThat(store.get("BB")).isEqualTo(new StoreResult.Found("second"));
        assertThat(store.size()).isEqualTo(2);

        store.delete("Aa");
        assertThat(store.get("Aa")).isInstanceOf(StoreResult.NotFound.class);
        assertThat(store.get("BB")).isEqualTo(new StoreResult.Found("second"));
    }

    @Test
    void resizeCorrectlyPreservesAllEntries() {
        // Default per-segment initial capacity is 16 with load factor 0.75, and there are
        // 16 segments, so a few thousand keys is enough to force every segment through
        // several resizes and still keep the test fast.
        int n = 5_000;
        for (int i = 0; i < n; i++) {
            store.put("key-" + i, "value-" + i);
        }

        assertThat(store.size()).isEqualTo(n);
        for (int i = 0; i < n; i++) {
            assertThat(store.get("key-" + i)).isEqualTo(new StoreResult.Found("value-" + i));
        }
    }

    @Test
    void emptyStringKeyAndValueAreValidBoundaryCases() {
        assertThat(store.put("", "")).isInstanceOf(StoreResult.Created.class);
        assertThat(store.get("")).isEqualTo(new StoreResult.Found(""));
    }

    @Test
    void keyAtExactlyMaxLengthIsAccepted() {
        StripedHashMapStore bounded = new StripedHashMapStore(4, new StoreLimits(8, 8, 1000));
        String key = "12345678"; // exactly 8 chars
        assertThat(bounded.put(key, "v")).isInstanceOf(StoreResult.Created.class);
    }

    @Test
    void keyOverMaxLengthIsRejectedWithCapacityExceeded() {
        StripedHashMapStore bounded = new StripedHashMapStore(4, new StoreLimits(8, 8, 1000));
        assertThat(bounded.put("123456789", "v")).isInstanceOf(StoreResult.CapacityExceeded.class);
    }

    @Test
    void valueOverMaxLengthIsRejectedWithCapacityExceeded() {
        StripedHashMapStore bounded = new StripedHashMapStore(4, new StoreLimits(64, 8, 1000));
        assertThat(bounded.put("k", "123456789")).isInstanceOf(StoreResult.CapacityExceeded.class);
    }

    @Test
    void maxEntriesPerNodeIsEnforcedButUpdatesToExistingKeysStillSucceed() {
        StripedHashMapStore bounded = new StripedHashMapStore(4, new StoreLimits(64, 64, 2));
        assertThat(bounded.put("a", "1")).isInstanceOf(StoreResult.Created.class);
        assertThat(bounded.put("b", "1")).isInstanceOf(StoreResult.Created.class);
        assertThat(bounded.put("c", "1")).isInstanceOf(StoreResult.CapacityExceeded.class);

        // Updating a key that already exists must still work even though the node is "full".
        assertThat(bounded.put("a", "2")).isInstanceOf(StoreResult.Updated.class);
        assertThat(bounded.get("a")).isEqualTo(new StoreResult.Found("2"));
    }

    @Test
    void rejectsNonPowerOfTwoSegmentCount() {
        assertThrows(IllegalArgumentException.class, () -> new StripedHashMapStore(15, StoreLimits.defaults()));
    }
}
