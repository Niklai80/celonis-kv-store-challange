package com.example.kvstore.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class KeyValueControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void fullLifecycleOfASimpleKey() throws Exception {
        mockMvc.perform(put("/api/v1/keys").param("key", "greeting")
                        .contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/keys/lookup").param("key", "greeting"))
                .andExpect(status().isOk())
                .andExpect(content().string("hello"));

        // Same key again -> 200 Updated, not 201.
        mockMvc.perform(put("/api/v1/keys").param("key", "greeting")
                        .contentType(MediaType.TEXT_PLAIN).content("hi"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/keys/lookup").param("key", "greeting"))
                .andExpect(content().string("hi"));

        mockMvc.perform(delete("/api/v1/keys").param("key", "greeting"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/keys/lookup").param("key", "greeting"))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/keys").param("key", "greeting"))
                .andExpect(status().isNotFound());
    }

    @Test
    void keyContainingSlashSpaceAndPercentRoundTripsCorrectly() throws Exception {
        assertRoundTrip("a/b c%20d", "value-for-slash-key");
    }

    @Test
    void unicodeKeyRoundTripsCorrectly() throws Exception {
        assertRoundTrip("café-☕-日本語", "unicode-value-日本語");
    }

    private void assertRoundTrip(String key, String value) throws Exception {
        URI putUri = UriComponentsBuilder.fromPath("/api/v1/keys")
                .queryParam("key", "{key}").build(key);
        mockMvc.perform(put(putUri).contentType(MediaType.TEXT_PLAIN).content(value))
                .andExpect(status().isCreated());

        URI getUri = UriComponentsBuilder.fromPath("/api/v1/keys/lookup")
                .queryParam("key", "{key}").build(key);
        mockMvc.perform(get(getUri))
                .andExpect(status().isOk())
                .andExpect(content().string(value));

        URI deleteUri = UriComponentsBuilder.fromPath("/api/v1/keys")
                .queryParam("key", "{key}").build(key);
        mockMvc.perform(delete(deleteUri)).andExpect(status().isNoContent());
    }

    @Test
    void listKeysReturnsUpToLimit() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(put("/api/v1/keys").param("key", "list-" + i)
                    .contentType(MediaType.TEXT_PLAIN).content("v"));
        }

        mockMvc.perform(get("/api/v1/keys").param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.keys.length()").value(3));
    }

    @Test
    void statsReportsEntryCountAndHeap() throws Exception {
        mockMvc.perform(put("/api/v1/keys").param("key", "stats-key")
                .contentType(MediaType.TEXT_PLAIN).content("v"));

        mockMvc.perform(get("/api/v1/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entryCount").isNumber())
                .andExpect(jsonPath("$.heapUsedBytes").isNumber())
                .andExpect(jsonPath("$.shardCount").value(1));
    }

    @Test
    void ownerLookupReturnsShardZeroInSingleNodeMode() throws Exception {
        mockMvc.perform(get("/api/v1/keys/owner").param("key", "anything"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shardIndex").value(0));
    }

    @Test
    void missingKeyParameterReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/keys/lookup")).andExpect(status().isBadRequest());
    }
}
