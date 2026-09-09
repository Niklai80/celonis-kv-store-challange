package com.example.kvstore.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "kvstore.limits.max-key-length=8",
                "kvstore.limits.max-value-length=8",
                "kvstore.limits.max-entries-per-node=2"
        })
@AutoConfigureMockMvc
class KeyValueControllerCapacityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void keyOverMaxLengthReturns507() throws Exception {
        mockMvc.perform(put("/api/v1/keys").param("key", "way-too-long-a-key")
                        .contentType(MediaType.TEXT_PLAIN).content("v"))
                .andExpect(status().isInsufficientStorage());
    }

    @Test
    void valueOverMaxLengthReturns507() throws Exception {
        mockMvc.perform(put("/api/v1/keys").param("key", "k")
                        .contentType(MediaType.TEXT_PLAIN).content("way-too-long-a-value"))
                .andExpect(status().isInsufficientStorage());
    }

    @Test
    void nodeEntryLimitReturns507ButUpdatingAnExistingKeyStillWorks() throws Exception {
        mockMvc.perform(put("/api/v1/keys").param("key", "a").contentType(MediaType.TEXT_PLAIN).content("1"))
                .andExpect(status().isCreated());
        mockMvc.perform(put("/api/v1/keys").param("key", "b").contentType(MediaType.TEXT_PLAIN).content("1"))
                .andExpect(status().isCreated());
        mockMvc.perform(put("/api/v1/keys").param("key", "c").contentType(MediaType.TEXT_PLAIN).content("1"))
                .andExpect(status().isInsufficientStorage());

        mockMvc.perform(put("/api/v1/keys").param("key", "a").contentType(MediaType.TEXT_PLAIN).content("2"))
                .andExpect(status().isOk());
    }
}
