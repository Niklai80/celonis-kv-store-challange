package com.example.kvstore.api;

import com.example.kvstore.cluster.ClusterConfig;
import com.example.kvstore.cluster.PeerClient;
import com.example.kvstore.cluster.ShardRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the controller-level loop-prevention check: a request carrying X-KV-Forwarded is
 * never re-forwarded, and gets 421 if this node turns out not to own the key. SHARD_COUNT=3
 * with no real peers running means an *un-forwarded* request for a non-owned key would actually
 * try (and fail) to reach a peer - see the "peer unreachable" test, which doubles as coverage
 * of the real forwarding attempt.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "SHARD_COUNT=3",
                "HOSTNAME=kv-store-0",
                "kvstore.cluster.validation.enabled=false" // no real peer DNS to validate against in this test
        })
@AutoConfigureMockMvc
class KeyValueControllerClusterTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ClusterConfig clusterConfig;

    private String localKey;
    private String remoteKey;

    @BeforeEach
    void findKeysByOwnership() {
        ShardRouter router = new ShardRouter(clusterConfig);
        localKey = findKeyOwnedBy(router, clusterConfig.shardIndex());
        remoteKey = findKeyOwnedByOtherThan(router, clusterConfig.shardIndex());
    }

    @Test
    void forwardedRequestForAnOwnedKeyIsServedLocally() throws Exception {
        mockMvc.perform(put("/api/v1/keys").param("key", localKey)
                        .header(PeerClient.FORWARDED_HEADER, "true")
                        .contentType(MediaType.TEXT_PLAIN).content("v"))
                .andExpect(status().isCreated());
    }

    @Test
    void forwardedRequestForANonOwnedKeyReturns421InsteadOfForwardingAgain() throws Exception {
        mockMvc.perform(put("/api/v1/keys").param("key", remoteKey)
                        .header(PeerClient.FORWARDED_HEADER, "true")
                        .contentType(MediaType.TEXT_PLAIN).content("v"))
                .andExpect(status().is(421));
    }

    @Test
    void unforwardedRequestForANonOwnedKeyAttemptsRealForwardingAndFailsCleanlyWhenThePeerIsUnreachable() throws Exception {
        // No peers are actually running in this test, so ForwardingStore's attempt to proxy
        // this to the owning shard fails at the network layer - proving the request really was
        // routed outward, not silently absorbed locally.
        mockMvc.perform(put("/api/v1/keys").param("key", remoteKey)
                        .contentType(MediaType.TEXT_PLAIN).content("v"))
                .andExpect(status().isBadGateway());
    }

    @Test
    void forwardedListKeysRequestAnswersLocallyWithoutFanningOutToPeers() throws Exception {
        // If this recursed into scatter-gather, it would try to reach 2 nonexistent peers and
        // fail; answering 200 here proves the forwarded header suppressed the fan-out.
        mockMvc.perform(get("/api/v1/keys").param("limit", "10")
                        .header(PeerClient.FORWARDED_HEADER, "true"))
                .andExpect(status().isOk());
    }

    @Test
    void unforwardedListKeysRequestDoesFanOutAndFailsWhenPeersAreUnreachable() throws Exception {
        mockMvc.perform(get("/api/v1/keys").param("limit", "10"))
                .andExpect(status().isBadGateway());
    }

    private static String findKeyOwnedBy(ShardRouter router, int shard) {
        for (int i = 0; i < 10_000; i++) {
            String key = "k" + i;
            if (router.ownerOf(key) == shard) {
                return key;
            }
        }
        throw new IllegalStateException("no key found owned by shard " + shard);
    }

    private static String findKeyOwnedByOtherThan(ShardRouter router, int shard) {
        for (int i = 0; i < 10_000; i++) {
            String key = "k" + i;
            if (router.ownerOf(key) != shard) {
                return key;
            }
        }
        throw new IllegalStateException("no key found NOT owned by shard " + shard);
    }
}
