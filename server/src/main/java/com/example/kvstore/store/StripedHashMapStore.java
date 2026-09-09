package com.example.kvstore.store;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongSupplier;

/**
 * A hand-rolled concurrent hash map, string keys to string values only.
 *
 * <p><b>Why not just use {@code ConcurrentHashMap}?</b> The challenge explicitly asks for a
 * from-scratch implementation so the concurrency reasoning is visible and defensible, not
 * hidden inside the JDK. See the README ADR "custom map vs ConcurrentHashMap" for the honest
 * trade-off: the JDK's implementation is faster and more sophisticated (lock-free reads, CAS
 * on empty-bin insert, treeified bins, cooperative resizing). This implementation trades that
 * for something whose concurrency reasoning is fully visible and explainable end-to-end.
 *
 * <p><b>Design: striping, not one global lock.</b> The table is split into a fixed number of
 * independent segments, each with its own {@link ReentrantReadWriteLock}, bucket array and
 * entry count. A key belongs to exactly one segment (chosen by a spread hash of the key), so
 * two threads touching different segments never contend. This is the same idea Java 7's
 * {@code ConcurrentHashMap} used before Java 8 moved to per-bucket locking - coarser than
 * per-bucket, but far simpler to reason about and still gives real parallelism for the
 * segment count we use (default 16).
 *
 * <p><b>Why {@code ReentrantReadWriteLock} and not {@code synchronized}?</b> Two reasons.
 * First, GET is far more common than PUT/DELETE in most workloads, and a read-write lock lets
 * concurrent readers proceed together while writers still get exclusive access. Second - and
 * specific to this project - Spring Boot here runs on virtual threads
 * ({@code spring.threads.virtual.enabled=true}). A {@code synchronized} block "pins" a virtual
 * thread to its carrier platform thread for the duration of the block (JEP 444), which defeats
 * the point of virtual threads under load. {@code java.util.concurrent.locks} locks do not pin.
 *
 * <p><b>Memory visibility without {@code volatile} fields on the node.</b> Every read goes
 * through the segment's read lock and every write through its write lock. The Java Memory
 * Model guarantees a happens-before edge between a lock's {@code unlock()} and the next
 * thread's {@code lock()} on the same lock (JLS 17.4.4) - so once a write lock is released,
 * every subsequent reader (via the read lock) is guaranteed to see the write. That happens-before
 * edge is what makes the plain (non-volatile) fields on {@link Node} safe here; without it, a
 * reader on another core could observe stale data indefinitely, since nothing in the Java
 * Memory Model requires cross-thread visibility of plain field writes.
 */
public final class StripedHashMapStore implements KeyValueStore {

    /** Must be a power of two so segment/bucket selection can use a fast bitmask instead of {@code %}. */
    private static final int DEFAULT_SEGMENT_COUNT = 16;
    private static final int INITIAL_TABLE_CAPACITY = 16;
    private static final float LOAD_FACTOR = 0.75f;

    private final Segment[] segments;
    private final int segmentMask;
    private final int segmentIndexShift;
    private final StoreLimits limits;

    public StripedHashMapStore() {
        this(DEFAULT_SEGMENT_COUNT, StoreLimits.defaults());
    }

    public StripedHashMapStore(int segmentCount, StoreLimits limits) {
        if (segmentCount <= 0 || (segmentCount & (segmentCount - 1)) != 0) {
            throw new IllegalArgumentException("segmentCount must be a power of two, got " + segmentCount);
        }
        this.limits = limits;
        // Built as a local array first, then assigned to the field, so the totalSize lambda
        // below can close over 'local' directly instead of capturing 'this' from inside a
        // constructor that hasn't finished initializing yet.
        Segment[] local = new Segment[segmentCount];
        LongSupplier totalSize = () -> {
            long total = 0;
            for (Segment s : local) {
                total += s.count.get();
            }
            return total;
        };
        for (int i = 0; i < segmentCount; i++) {
            local[i] = new Segment(totalSize);
        }
        this.segments = local;
        this.segmentMask = segmentCount - 1;
        // Segment selection uses the TOP bits of the spread hash, bucket selection (inside a
        // segment) uses the LOW bits of the same spread hash. Splitting the bit ranges this way
        // means the two decisions don't correlate - two keys landing in the same segment don't
        // systematically land in the same bucket within it, which is exactly what "spread the
        // hash before use, use a different function for shard routing than for bucket indexing"
        // is protecting against at a smaller scale.
        this.segmentIndexShift = 32 - Integer.numberOfTrailingZeros(segmentCount);
    }

    @Override
    public StoreResult put(String key, String value) {
        requireNonNull(key, value);
        if (key.length() > limits.maxKeyLength()) {
            return new StoreResult.CapacityExceeded("key exceeds max length " + limits.maxKeyLength());
        }
        if (value.length() > limits.maxValueLength()) {
            return new StoreResult.CapacityExceeded("value exceeds max length " + limits.maxValueLength());
        }
        int hash = spread(key.hashCode());
        Segment segment = segmentFor(hash);
        return segment.put(key, hash, value, limits.maxEntriesPerNode());
    }

    @Override
    public StoreResult get(String key) {
        if (key == null) {
            throw new NullPointerException("key must not be null");
        }
        int hash = spread(key.hashCode());
        String value = segmentFor(hash).get(key, hash);
        return value == null ? new StoreResult.NotFound() : new StoreResult.Found(value);
    }

    @Override
    public StoreResult delete(String key) {
        if (key == null) {
            throw new NullPointerException("key must not be null");
        }
        int hash = spread(key.hashCode());
        boolean removed = segmentFor(hash).remove(key, hash);
        return removed ? new StoreResult.Deleted() : new StoreResult.NotFound();
    }

    @Override
    public long size() {
        // Sum of per-segment counters, none of them taken under a lock. Between reading
        // segment[0]'s count and segment[1]'s count, either can change. This is intentional:
        // a linearizable size() would need to freeze every segment (all 16 write locks) for
        // the duration of the call, which turns an O(1)-ish stat read into a full stop-the-world
        // pause. We accept "approximately right, updated a moment ago" instead - fine for a
        // /stats endpoint, wrong if you needed it for a correctness decision.
        long total = 0;
        for (Segment segment : segments) {
            total += segment.count.get();
        }
        return total;
    }

    /** Iterates every entry across every segment, each segment snapshotted under its own read lock. */
    @Override
    public List<String> keys(int limit) {
        List<String> result = new ArrayList<>();
        for (Segment segment : segments) {
            segment.collectKeys(result, limit);
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    private static void requireNonNull(String key, String value) {
        // A concurrent map cannot treat null as "absent" the way a single-threaded caller might
        // expect: get(key) == null is ambiguous between "no such key" and "key mapped to null",
        // and that ambiguity is exactly what ConcurrentHashMap's own javadoc calls out as the
        // reason it rejects nulls outright. We do the same, and return NotFound/Found explicitly
        // instead of ever encoding "absent" as a null return value.
        if (key == null) {
            throw new NullPointerException("key must not be null");
        }
        if (value == null) {
            throw new NullPointerException("value must not be null");
        }
    }

    /** Spreads high bits into the low bits so a poor {@code hashCode()} doesn't concentrate into one bucket. */
    private static int spread(int h) {
        return h ^ (h >>> 16);
    }

    private Segment segmentFor(int spreadHash) {
        int idx = (spreadHash >>> segmentIndexShift) & segmentMask;
        return segments[idx];
    }

    /** Singly-linked chain node. Deliberately not generic - this store is String-to-String only. */
    private static final class Node {
        final String key;
        final int hash;
        String value;
        Node next;

        Node(String key, int hash, String value, Node next) {
            this.key = key;
            this.hash = hash;
            this.value = value;
            this.next = next;
        }
    }

    /** One independently-locked shard of the map: its own table, its own lock, its own count. */
    private static final class Segment {
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        private final AtomicInteger count = new AtomicInteger(0);
        private final LongSupplier totalSize;
        private Node[] table = new Node[INITIAL_TABLE_CAPACITY];

        Segment(LongSupplier totalSize) {
            this.totalSize = totalSize;
        }

        StoreResult put(String key, int hash, String value, int maxEntriesPerNode) {
            lock.writeLock().lock();
            try {
                int idx = indexFor(hash, table.length);
                for (Node n = table[idx]; n != null; n = n.next) {
                    if (n.hash == hash && n.key.equals(key)) {
                        n.value = value;
                        return new StoreResult.Updated();
                    }
                }
                // New key. Admission control happens here, not before the loop above, so
                // updating an existing key never fails on a full store - only growth does.
                // totalSize sums every segment's AtomicInteger without locking any of them
                // (same approximation as size()), so this check is safe to make while we
                // hold this segment's own write lock.
                if (totalSize.getAsLong() >= maxEntriesPerNode) {
                    return new StoreResult.CapacityExceeded("node entry limit reached: " + maxEntriesPerNode);
                }
                table[idx] = new Node(key, hash, value, table[idx]);
                int newCount = count.incrementAndGet();
                if (newCount > table.length * LOAD_FACTOR) {
                    resize();
                }
                return new StoreResult.Created();
            } finally {
                lock.writeLock().unlock();
            }
        }

        String get(String key, int hash) {
            lock.readLock().lock();
            try {
                int idx = indexFor(hash, table.length);
                for (Node n = table[idx]; n != null; n = n.next) {
                    if (n.hash == hash && n.key.equals(key)) {
                        return n.value;
                    }
                }
                return null;
            } finally {
                lock.readLock().unlock();
            }
        }

        boolean remove(String key, int hash) {
            lock.writeLock().lock();
            try {
                int idx = indexFor(hash, table.length);
                Node prev = null;
                for (Node n = table[idx]; n != null; n = n.next) {
                    if (n.hash == hash && n.key.equals(key)) {
                        if (prev == null) {
                            table[idx] = n.next;
                        } else {
                            prev.next = n.next;
                        }
                        count.decrementAndGet();
                        return true;
                    }
                    prev = n;
                }
                return false;
            } finally {
                lock.writeLock().unlock();
            }
        }

        void collectKeys(List<String> out, int limit) {
            lock.readLock().lock();
            try {
                for (Node bucket : table) {
                    for (Node n = bucket; n != null; n = n.next) {
                        if (out.size() >= limit) {
                            return;
                        }
                        out.add(n.key);
                    }
                }
            } finally {
                lock.readLock().unlock();
            }
        }

        /** Doubles the table and rehashes every entry. Runs under the write lock, so it's safe but stops the segment. */
        private void resize() {
            Node[] oldTable = table;
            Node[] newTable = new Node[oldTable.length * 2];
            for (Node bucket : oldTable) {
                Node n = bucket;
                while (n != null) {
                    Node next = n.next;
                    int idx = indexFor(n.hash, newTable.length);
                    n.next = newTable[idx];
                    newTable[idx] = n;
                    n = next;
                }
            }
            table = newTable;
        }

        private static int indexFor(int hash, int tableLength) {
            // hash is already spread (see StripedHashMapStore.spread); '&' against length-1
            // works because tableLength is always a power of two, and unlike '%' it's well
            // defined for the negative ints a hashCode() can produce.
            return hash & (tableLength - 1);
        }
    }
}
