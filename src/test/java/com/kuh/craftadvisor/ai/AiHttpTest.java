package com.kuh.craftadvisor.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AiHttpTest {
    private static final String KEY = "sk-test-FAKE-KEY-0123456789";

    @Test
    void statusCodesBecomeErrorKinds() {
        assertEquals(AiException.Kind.BAD_KEY, AiHttp.errorOf(401, "", KEY, false).kind());
        assertEquals(AiException.Kind.RATE_LIMITED, AiHttp.errorOf(429, "", KEY, false).kind());
        assertEquals(AiException.Kind.OVERLOADED, AiHttp.errorOf(500, "", KEY, false).kind());
        assertEquals(AiException.Kind.BAD_REQUEST, AiHttp.errorOf(400, "", KEY, false).kind());
    }

    @Test
    void badRequestForToolsOrSchemaMeansTheModelDoesNotSupportThem() {
        String body = "{\"error\":{\"message\":\"tools are not supported\",\"type\":\"invalid_request_error\"}}";

        AiException error = AiHttp.errorOf(400, body, KEY, true);

        assertEquals(AiException.Kind.UNSUPPORTED, error.kind());
        assertEquals("tools are not supported", error.getMessage());
        assertEquals(AiException.Kind.OVERLOADED, AiHttp.errorOf(503, body, KEY, true).kind());
    }

    @Test
    void errorMessagesNeverCarryTheKey() {
        String echoed = "{\"error\":{\"message\":\"Incorrect API key provided: " + KEY + "\"}}";

        assertFalse(AiHttp.errorOf(401, echoed, KEY, false).getMessage().contains("sk-test"));
        assertFalse(AiHttp.errorOf(400, echoed, KEY, false).getMessage().contains(KEY));
    }

    @Test
    void plainStringErrorsAndOtherBodiesAreRead() {
        assertEquals("model \"x\" not found", AiHttp.errorOf(404, "{\"error\":\"model \\\"x\\\" not found\"}", "", false).getMessage());
        assertEquals("HTTP 502", AiHttp.errorOf(502, "<html>bad gateway</html>", "", false).getMessage());
    }

    @Test
    void aKeyWithControlCharactersFailsBeforeSendingWithoutQuotingIt() {
        Map<String, String> headers = Map.of("authorization", "Bearer " + KEY + "\u0001");

        AiException error = assertThrows(AiException.class,
                () -> AiHttp.JDK.post(URI.create("https://localhost:1/v1"), headers, "{}"));

        assertEquals(AiException.Kind.BAD_KEY, error.kind());
        assertFalse(error.getMessage().contains("sk-test"));
    }
}
