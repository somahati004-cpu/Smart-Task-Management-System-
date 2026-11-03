package com.smarttask.main;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public final class JsonTest {
    private JsonTest() {
    }

    public static void main(String[] args) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("text", "quote: \" slash: \\ line:\n");
        value.put("enabled", true);
        value.put("items", Arrays.asList(3.0, null, "ok"));

        Object parsed = Json.parse(Json.stringify(value));
        if (!value.equals(parsed)) {
            throw new AssertionError("JSON round-trip did not preserve the object.");
        }

        expectInvalid("[1,]");
        expectInvalid("{\"key\":1,}");
        expectInvalid("{\"key\":1,\"key\":2}");
        expectInvalid("true false");
        System.out.println("JSON tests passed.");
    }

    private static void expectInvalid(String input) {
        try {
            Json.parse(input);
            throw new AssertionError("Expected invalid JSON to be rejected: " + input);
        } catch (IllegalArgumentException expected) {
            if (expected.getMessage() == null) {
                throw new AssertionError("Invalid JSON error should explain the failure.");
            }
        }
    }
}
