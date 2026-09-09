package com.example.kvstore.api.response;

import java.util.List;

public record KeysResponse(List<String> keys, int count) {

    public static KeysResponse of(List<String> keys) {
        return new KeysResponse(keys, keys.size());
    }
}
