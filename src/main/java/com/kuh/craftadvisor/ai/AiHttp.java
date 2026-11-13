package com.kuh.craftadvisor.ai;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuh.craftadvisor.CraftAdvisor;

/**
 * The HTTP part every provider shares: one JDK HttpClient (connection kept warm), one daemon thread for all AI calls,
 * one retry for temporary failures, and status codes as {@link AiException.Kind}. The API key only ever travels in
 * a request header; it is never logged and is removed from error messages.
 */
final class AiHttp {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(90);
    private static final long RETRY_DELAY_MS = 1500;
    private static final int OK = 200;
    private static final int BAD_REQUEST = 400;
    private static final String REDACTED = "***";
    /** Real answers are far smaller; a larger body is refused instead of filling memory. */
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "craftadvisor-ai");
        thread.setDaemon(true);
        return thread;
    });

    record Response(int status, String body) {
    }

    /** Sends one POST; a connection problem is {@link AiException.Kind#NETWORK}. Tests replace it. */
    @FunctionalInterface
    interface Transport {
        Response post(URI endpoint, Map<String, String> headers, String json);
    }

    static final Transport JDK = AiHttp::sendOnce;

    /**
     * What to send and how to report it. {@code label} names the API in logs; {@code secret} (the key) is cut out
     * of error messages; {@code structured} marks requests with tools or a response schema, whose 400 means the
     * model does not support them ({@link AiException.Kind#UNSUPPORTED}).
     */
    record Call(String label, URI endpoint, Map<String, String> headers, String json, String secret, boolean structured) {
        /** Headers and secret hold the key: never printed. */
        @Override
        public String toString() {
            return "Call[" + label + " " + endpoint + "]";
        }
    }

    private AiHttp() {
    }

    /** The 200 response body, on the AI thread; fails with {@link AiException}. */
    static CompletableFuture<String> post(Transport transport, Call call) {
        return CompletableFuture.supplyAsync(() -> sendWithRetry(transport, call), EXECUTOR);
    }

    private static String sendWithRetry(Transport transport, Call call) {
        try {
            return sendChecked(transport, call);
        } catch (AiException e) {
            boolean retryable = e.kind() == AiException.Kind.RATE_LIMITED
                    || e.kind() == AiException.Kind.OVERLOADED
                    || e.kind() == AiException.Kind.NETWORK;
            if (!retryable) {
                throw e;
            }
            sleep(RETRY_DELAY_MS);
            return sendChecked(transport, call);
        }
    }

    private static String sendChecked(Transport transport, Call call) {
        Response response = transport.post(call.endpoint(), call.headers(), call.json());
        if (response.status() == OK) {
            return response.body();
        }
        CraftAdvisor.LOGGER.warn("{} API error: status={} type={}", call.label(), response.status(),
                redact(errorType(response.body()), call.secret()));
        throw errorOf(response.status(), response.body(), call.secret(), call.structured());
    }

    private static Response sendOnce(URI endpoint, Map<String, String> headers, String json) {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(json));
        try {
            headers.forEach(request::header);
        } catch (IllegalArgumentException e) {
            // The JDK message quotes the header value, which holds the key: never pass it on.
            throw new AiException(AiException.Kind.BAD_KEY, "invalid characters in the API key");
        }
        if ("http".equalsIgnoreCase(endpoint.getScheme())) {
            request.version(HttpClient.Version.HTTP_1_1); // local servers: no h2c upgrade attempt
        }
        try {
            HttpResponse<InputStream> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                byte[] bytes = body.readNBytes(MAX_BODY_BYTES + 1);
                if (bytes.length > MAX_BODY_BYTES) {
                    throw new AiException(AiException.Kind.BAD_RESPONSE, "response larger than 2 MB");
                }
                return new Response(response.statusCode(), new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new AiException(AiException.Kind.NETWORK, e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException(AiException.Kind.NETWORK, "interrupted", e);
        }
    }

    static AiException.Kind kindOf(int status) {
        return switch (status) {
            case 401, 403 -> AiException.Kind.BAD_KEY;
            case 429 -> AiException.Kind.RATE_LIMITED;
            case 500, 502, 503, 504, 529 -> AiException.Kind.OVERLOADED;
            default -> AiException.Kind.BAD_REQUEST;
        };
    }

    /**
     * The error for a non-200 response. The server's message is kept (without the key) except for a rejected key,
     * where some APIs echo part of the key back.
     */
    static AiException errorOf(int status, String body, String secret, boolean structured) {
        AiException.Kind kind = structured && status == BAD_REQUEST ? AiException.Kind.UNSUPPORTED : kindOf(status);
        String message = "HTTP " + status;
        String serverMessage = errorMessage(body);
        if (kind != AiException.Kind.BAD_KEY && !serverMessage.isEmpty()) {
            message = redact(serverMessage, secret);
        }
        return new AiException(kind, message);
    }

    /** {@code error.message} of {"error": {...}} (Anthropic, OpenAI) or the text of {"error": "..."} (some servers). */
    private static String errorMessage(String body) {
        JsonElement error = errorElement(body);
        if (error instanceof JsonObject object && object.get("message") != null && object.get("message").isJsonPrimitive()) {
            return object.get("message").getAsString();
        }
        return error != null && error.isJsonPrimitive() ? error.getAsString() : "";
    }

    private static String errorType(String body) {
        JsonElement error = errorElement(body);
        if (error instanceof JsonObject object) {
            JsonElement type = object.get("type") != null ? object.get("type") : object.get("code");
            return type != null && type.isJsonPrimitive() ? type.getAsString() : "";
        }
        return "";
    }

    private static JsonElement errorElement(String body) {
        try {
            return JsonParser.parseString(body).getAsJsonObject().get("error");
        } catch (RuntimeException e) {
            return null; // not the documented error envelope; the status code is enough
        }
    }

    static String redact(String text, String secret) {
        return secret == null || secret.isBlank() ? text : text.replace(secret, REDACTED);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
