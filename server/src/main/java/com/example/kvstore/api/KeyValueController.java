package com.example.kvstore.api;

import com.example.kvstore.api.response.KeysResponse;
import com.example.kvstore.api.response.NodeStatsResponse;
import com.example.kvstore.api.response.OwnerResponse;
import com.example.kvstore.cluster.PeerClient;
import com.example.kvstore.cluster.ShardRouter;
import com.example.kvstore.store.KeyValueStore;
import com.example.kvstore.store.StoreResult;
import com.example.kvstore.store.StripedHashMapStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Keys are arbitrary strings (may contain '/', spaces, '%', unicode). Rather than fighting
 * Tomcat's default rejection of encoded slashes in path segments, the key travels as a query
 * parameter on every endpoint - Spring decodes query parameters as UTF-8 out of the box, so
 * "a/b c%20d" and unicode keys round-trip correctly with zero extra container configuration.
 * See the README "key encoding" section for the alternative that was rejected.
 *
 * <p><b>Loop prevention.</b> A request forwarded from a peer carries
 * {@value PeerClient#FORWARDED_HEADER}: true. When that header is present, this controller
 * never re-forwards - it either serves the key from the local store (if this node really is the
 * owner) or returns 421 Misdirected Request (if it isn't, which would otherwise be the seed of a
 * forwarding loop). The un-forwarded path is the only one allowed to consult ForwardingStore and
 * potentially proxy onward.
 */
@RestController
public class KeyValueController {

    // Spring's HttpStatus enum (as of spring-web 6.1) has no 421 constant, so the raw code is
    // used with ResponseEntity.status(int) instead.
    private static final int MISDIRECTED_REQUEST = 421;

    /** Cluster-aware store: decides per key whether to answer locally or proxy to the owner. Used only for un-forwarded requests. */
    private final KeyValueStore clusterStore;

    /** The local store, bypassing routing entirely. Used for forwarded requests, which must never be forwarded again. */
    private final StripedHashMapStore localStore;

    private final ShardRouter router;
    private final NodeStatsService nodeStatsService;

    public KeyValueController(KeyValueStore clusterStore, StripedHashMapStore localStore,
                               ShardRouter router, NodeStatsService nodeStatsService) {
        this.clusterStore = clusterStore;
        this.localStore = localStore;
        this.router = router;
        this.nodeStatsService = nodeStatsService;
    }

    @PutMapping(value = "/api/v1/keys", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> put(@RequestParam String key, @RequestBody(required = false) String value,
                                       @RequestHeader(value = PeerClient.FORWARDED_HEADER, required = false) boolean forwarded) {
        ResponseEntity<String> misdirected = rejectIfMisdirected(key, forwarded);
        if (misdirected != null) {
            return misdirected;
        }
        StoreResult result = store(forwarded).put(key, value == null ? "" : value);
        return switch (result) {
            case StoreResult.Created ignored -> ResponseEntity.status(HttpStatus.CREATED).build();
            case StoreResult.Updated ignored -> ResponseEntity.ok().build();
            case StoreResult.CapacityExceeded exceeded ->
                    ResponseEntity.status(HttpStatus.INSUFFICIENT_STORAGE).body(exceeded.reason());
            case StoreResult.Found ignored -> throw unreachable(result);
            case StoreResult.NotFound ignored -> throw unreachable(result);
            case StoreResult.Deleted ignored -> throw unreachable(result);
        };
    }

    @GetMapping(value = "/api/v1/keys/lookup", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> get(@RequestParam String key,
                                       @RequestHeader(value = PeerClient.FORWARDED_HEADER, required = false) boolean forwarded) {
        ResponseEntity<String> misdirected = rejectIfMisdirected(key, forwarded);
        if (misdirected != null) {
            return misdirected;
        }
        StoreResult result = store(forwarded).get(key);
        return switch (result) {
            case StoreResult.Found found -> ResponseEntity.ok(found.value());
            case StoreResult.NotFound ignored -> ResponseEntity.notFound().build();
            case StoreResult.Created ignored -> throw unreachable(result);
            case StoreResult.Updated ignored -> throw unreachable(result);
            case StoreResult.Deleted ignored -> throw unreachable(result);
            case StoreResult.CapacityExceeded ignored -> throw unreachable(result);
        };
    }

    @DeleteMapping("/api/v1/keys")
    public ResponseEntity<Void> delete(@RequestParam String key,
                                        @RequestHeader(value = PeerClient.FORWARDED_HEADER, required = false) boolean forwarded) {
        ResponseEntity<Void> misdirected = forwarded && !router.isLocal(key)
                ? ResponseEntity.status(MISDIRECTED_REQUEST).<Void>build()
                : null;
        if (misdirected != null) {
            return misdirected;
        }
        StoreResult result = store(forwarded).delete(key);
        return switch (result) {
            case StoreResult.Deleted ignored -> ResponseEntity.noContent().build();
            case StoreResult.NotFound ignored -> ResponseEntity.notFound().build();
            case StoreResult.Created ignored -> throw unreachable(result);
            case StoreResult.Updated ignored -> throw unreachable(result);
            case StoreResult.Found ignored -> throw unreachable(result);
            case StoreResult.CapacityExceeded ignored -> throw unreachable(result);
        };
    }

    @GetMapping("/api/v1/keys")
    public KeysResponse listKeys(@RequestParam(defaultValue = "100") int limit,
                                  @RequestHeader(value = PeerClient.FORWARDED_HEADER, required = false) boolean forwarded) {
        // A forwarded list request must answer from this node only - if it also scatter-gathered
        // from its own peers, a 3-shard cluster would return each key up to 3 times.
        return KeysResponse.of(store(forwarded).keys(limit));
    }

    @GetMapping("/api/v1/stats")
    public NodeStatsResponse stats() {
        return nodeStatsService.currentStats();
    }

    /** Which shard owns a key, for the UI's cluster view - not part of the sharding path itself, just informational. */
    @GetMapping("/api/v1/keys/owner")
    public OwnerResponse owner(@RequestParam String key) {
        return new OwnerResponse(key, router.ownerOf(key));
    }

    private ResponseEntity<String> rejectIfMisdirected(String key, boolean forwarded) {
        return forwarded && !router.isLocal(key)
                ? ResponseEntity.status(MISDIRECTED_REQUEST).body("not the owner of key: " + key)
                : null;
    }

    private KeyValueStore store(boolean forwarded) {
        return forwarded ? localStore : clusterStore;
    }

    /**
     * The sealed StoreResult type spans all operations (Created/Updated/Found/NotFound/Deleted/
     * CapacityExceeded), but any single operation only ever produces a subset of them - put()
     * never returns Found, for instance. The switch above still has to be exhaustive because the
     * compiler only knows the static type, so the impossible branches assert that invariant
     * instead of silently mapping to some arbitrary HTTP status.
     */
    private static IllegalStateException unreachable(StoreResult result) {
        return new IllegalStateException("unexpected store result for this operation: " + result);
    }
}
