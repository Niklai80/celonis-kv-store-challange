package com.example.kvstore.cluster;

import com.example.kvstore.store.StoreResult;
import com.example.kvstore.store.StripedHashMapStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ForwardingStore's own decision logic (owner -> local, non-owner -> proxy) is what's under
 * test here, with PeerClient mocked out entirely - no network, no real peers. The 421
 * loop-prevention behavior itself lives in KeyValueController (it has to read the inbound
 * request header, which ForwardingStore never sees) and is covered separately by
 * KeyValueControllerClusterTest; what's verified here is the piece ForwardingStore is
 * actually responsible for for loop safety: a local key never touches the network at all.
 */
class ForwardingStoreTest {

    private static final int SELF_SHARD = 1;
    private static final int SHARD_COUNT = 4;

    private StripedHashMapStore local;
    private ShardRouter router;
    private PeerClient peerClient;
    private ForwardingStore store;

    private String localKey;
    private int remoteShard;
    private String remoteKey;

    @BeforeEach
    void setUp() {
        local = new StripedHashMapStore();
        ClusterConfig clusterConfig = new ClusterConfig(SHARD_COUNT, "kv-store-" + SELF_SHARD,
                "kv-store", "kv-store-headless", "default", 8080);
        router = new ShardRouter(clusterConfig);
        peerClient = mock(PeerClient.class);
        store = new ForwardingStore(local, router, peerClient);

        localKey = findKeyOwnedBy(SELF_SHARD);
        remoteKey = findKeyOwnedByOtherThan(SELF_SHARD);
        remoteShard = router.ownerOf(remoteKey);
    }

    @Test
    void ownedKeyIsServedFromLocalStoreAndNeverTouchesThePeerClient() {
        StoreResult result = store.put(localKey, "v1");

        assertThat(result).isInstanceOf(StoreResult.Created.class);
        assertThat(local.get(localKey)).isEqualTo(new StoreResult.Found("v1"));
        verifyNoInteractions(peerClient);
    }

    @Test
    void nonOwnedKeyIsProxiedToTheOwningPeerWithTheCorrectShardIndex() {
        when(peerClient.put(eq(remoteShard), eq(remoteKey), eq("v1"))).thenReturn(new StoreResult.Created());

        StoreResult result = store.put(remoteKey, "v1");

        assertThat(result).isInstanceOf(StoreResult.Created.class);
        assertThat(local.size()).isZero(); // must not also land in the local store
        verify(peerClient, times(1)).put(remoteShard, remoteKey, "v1");
    }

    @Test
    void getAndDeleteAlsoRouteByOwnership() {
        when(peerClient.get(remoteShard, remoteKey)).thenReturn(new StoreResult.Found("remote-value"));
        when(peerClient.delete(remoteShard, remoteKey)).thenReturn(new StoreResult.Deleted());

        assertThat(store.get(remoteKey)).isEqualTo(new StoreResult.Found("remote-value"));
        assertThat(store.delete(remoteKey)).isInstanceOf(StoreResult.Deleted.class);

        local.put(localKey, "local-value");
        assertThat(store.get(localKey)).isEqualTo(new StoreResult.Found("local-value"));
        verify(peerClient, never()).get(anyInt(), eq(localKey));
    }

    @Test
    void peerFailurePropagatesRatherThanBeingSwallowed() {
        when(peerClient.put(eq(remoteShard), any(), any()))
                .thenThrow(new PeerUnavailableException(remoteShard, "connection refused"));

        assertThatThrownBy(() -> store.put(remoteKey, "v1"))
                .isInstanceOf(PeerUnavailableException.class)
                .hasMessageContaining("shard " + remoteShard);
    }

    @Test
    void keysScatterGathersLocalEntriesAndEveryOtherShardButNeverItself() {
        local.put(localKey, "v");
        when(peerClient.listKeysLocal(anyInt(), anyInt())).thenReturn(List.of("remote-key"));

        List<String> keys = store.keys(100);

        assertThat(keys).contains(localKey).contains("remote-key");
        // 3 other shards in a 4-shard cluster, self excluded.
        verify(peerClient, times(SHARD_COUNT - 1)).listKeysLocal(anyInt(), anyInt());
        verify(peerClient, never()).listKeysLocal(eq(SELF_SHARD), anyInt());
    }

    private String findKeyOwnedBy(int shard) {
        for (int i = 0; i < 10_000; i++) {
            String key = "k" + i;
            if (router.ownerOf(key) == shard) {
                return key;
            }
        }
        throw new IllegalStateException("no key found owned by shard " + shard + " in search space");
    }

    private String findKeyOwnedByOtherThan(int shard) {
        for (int i = 0; i < 10_000; i++) {
            String key = "k" + i;
            if (router.ownerOf(key) != shard) {
                return key;
            }
        }
        throw new IllegalStateException("no key found NOT owned by shard " + shard + " in search space");
    }
}
