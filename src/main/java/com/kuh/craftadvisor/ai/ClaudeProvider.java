package com.kuh.craftadvisor.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.kuh.craftadvisor.CraftAdvisor;

/**
 * Claude Messages API over the JDK HttpClient (no extra libraries in the mod jar).
 * One shared HTTP/2 client keeps the connection warm; calls run on a single daemon thread.
 */
public final class ClaudeProvider implements AiProvider {
    private static final URI ENDPOINT = URI.create("https://api.anthropic.com/v1/messages");
    /** Where requests go; shown in the consent notice and the preview. */
    public static final String HOST = ENDPOINT.getHost();
    private static final String API_VERSION = "2026-10-21";
    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";
    /** Models documented to accept server-side refusal fallbacks. */
    private static final Set<String> FALLBACK_MODELS = Set.of(
            "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5", "claude-fable-5-1", "claude-fable-5-2");
    private static final int PICK_MAX_TOKENS = 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(90);
    private static final long RETRY_DELAY_MS = 1500;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "craftadvisor-ai");
        thread.setDaemon(true);
        return thread;
    });
    private static final Gson GSON = new Gson();

    private final String apiKey;
    private final String model;
    private final int answerMaxTokens;

    public ClaudeProvider(String apiKey, String model, int answerMaxTokens) {
        this.apiKey = apiKey;
        this.model = model;
        this.answerMaxTokens = answerMaxTokens;
    }

    @Override
    public CompletableFuture<ItemPick> pickItem(OutgoingRequest request) {
        return send(request).thenApply(text -> {
            try {
                return GSON.fromJson(text, ItemPick.class);
            } catch (JsonParseException e) {
                throw new AiException(AiException.Kind.BAD_RESPONSE, "item pick was not valid JSON", e);
            }
        });
    }

    @Override
    public CompletableFuture<String> answer(OutgoingRequest request) {
        return send(request);
    }

    @Override
    public CompletableFuture<AgentReply> converse(List<AgentMessage> messages, List<ToolSpec> tools, boolean toolsEnabled) {
        return post(GSON.toJson(converseBody(messages, tools, toolsEnabled))).thenApply(body -> {
            logUsage(body);
            return replyOf(body);
        });
    }

    /** Token usage only (shows whether the cache hits); never the request or response text. */
    private static void logUsage(String body) {
        if (!CraftAdvisor.LOGGER.isDebugEnabled()) {
            return;
        }
        try {
            JsonObject usage = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("usage");
            CraftAdvisor.LOGGER.debug("Claude usage: input={} cache_read={} cache_write={} output={}",
                    usage.get("input_tokens"), usage.get("cache_read_input_tokens"),
                    usage.get("cache_creation_input_tokens"), usage.get("output_tokens"));
        } catch (RuntimeException e) {
            CraftAdvisor.LOGGER.debug("Claude response had no readable usage");
        }
    }

    /** The JSON body for {@code request} (for a free question, the tool loop's first request); never the API key. */
    String body(OutgoingRequest request) {
        JsonArray messages = messages(request.history(), request.userText());
        JsonObject body = switch (request.route()) {
            case PICK -> {
                JsonObject pick = baseBody(Prompts.PICK, PICK_MAX_TOKENS, "low", messages);
                pick.getAsJsonObject("output_config").add("format", pickSchema());
                yield pick;
            }
            case ADVICE -> baseBody(Prompts.ADVICE, answerMaxTokens, "medium", messages);
            case CHAT -> converseBody(ToolLoop.firstMessages(request), request.tools(), true);
        };
        return GSON.toJson(body);
    }

    /**
     * Tools come first and keep the same list for the whole loop, so the cached prefix (tools, then the system
     * prompt) stays valid; the last tool and the system prompt carry the cache breakpoints.
     */
    JsonObject converseBody(List<AgentMessage> messages, List<ToolSpec> tools, boolean toolsEnabled) {
        JsonArray array = new JsonArray();
        messages.forEach(message -> array.add(messageJson(message)));
        JsonObject body = baseBody(Prompts.CHAT, answerMaxTokens, "medium", array);
        if (tools.isEmpty()) {
            return body;
        }
        JsonArray toolArray = new JsonArray();
        for (ToolSpec tool : tools) {
            JsonObject json = new JsonObject();
            json.addProperty("name", tool.name());
            json.addProperty("description", tool.description());
            json.add("input_schema", tool.inputSchema());
            toolArray.add(json);
        }
        toolArray.get(toolArray.size() - 1).getAsJsonObject().add("cache_control", ephemeral());
        body.add("tools", toolArray);
        JsonObject choice = new JsonObject();
        choice.addProperty("type", toolsEnabled ? "auto" : "none");
        body.add("tool_choice", choice);
        return body;
    }

    private static JsonObject messageJson(AgentMessage message) {
        return switch (message) {
            case AgentMessage.User user -> message("user", user.text());
            case AgentMessage.Assistant assistant when assistant.providerRaw() != null -> {
                JsonObject json = new JsonObject();
                json.addProperty("role", "assistant");
                json.add("content", assistant.providerRaw()); // thinking and tool_use blocks, unchanged
                yield json;
            }
            case AgentMessage.Assistant assistant -> message("assistant", assistant.text());
            case AgentMessage.ToolResults results -> toolResultsJson(results.results());
        };
    }

    private static JsonObject toolResultsJson(List<ToolResult> results) {
        JsonArray content = new JsonArray();
        for (ToolResult result : results) {
            JsonObject block = new JsonObject();
            block.addProperty("type", "tool_result");
            block.addProperty("tool_use_id", result.callId());
            block.addProperty("content", result.content());
            if (result.isError()) {
                block.addProperty("is_error", true);
            }
            content.add(block);
        }
        JsonObject json = new JsonObject();
        json.addProperty("role", "user");
        json.add("content", content);
        return json;
    }

    private JsonObject baseBody(String system, int maxTokens, String effort, JsonArray messages) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", maxTokens);
        JsonObject systemBlock = new JsonObject();
        systemBlock.addProperty("type", "text");
        systemBlock.addProperty("text", system);
        systemBlock.add("cache_control", ephemeral());
        JsonArray systemArray = new JsonArray();
        systemArray.add(systemBlock);
        body.add("system", systemArray);
        body.add("messages", messages);
        JsonObject outputConfig = new JsonObject();
        outputConfig.addProperty("effort", effort);
        body.add("output_config", outputConfig);
        if (FALLBACK_MODELS.contains(model)) {
            body.addProperty("fallbacks", "default");
        }
        return body;
    }

    private static JsonObject ephemeral() {
        JsonObject cache = new JsonObject();
        cache.addProperty("type", "ephemeral");
        return cache;
    }

    private static JsonArray messages(List<Turn> history, String user) {
        JsonArray array = new JsonArray();
        for (Turn turn : history) {
            array.add(message(turn.fromPlayer() ? "user" : "assistant", turn.text()));
        }
        array.add(message("user", user));
        return array;
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private static JsonObject pickSchema() {
        String schema = """
                {"type":"json_schema","schema":{"type":"object","additionalProperties":false,
                 "required":["intent","itemId","reason"],
                 "properties":{"intent":{"type":"string","enum":["craft","question"]},
                               "itemId":{"type":"string"},
                               "reason":{"type":"string"}}}}""";
        return JsonParser.parseString(schema).getAsJsonObject();
    }

    private CompletableFuture<String> send(OutgoingRequest request) {
        return post(body(request)).thenApply(ClaudeProvider::textOf);
    }

    /** The raw response body. */
    private CompletableFuture<String> post(String json) {
        if (apiKey == null || apiKey.isBlank()) {
            return CompletableFuture.failedFuture(new AiException(AiException.Kind.NO_KEY, "no API key"));
        }
        return CompletableFuture.supplyAsync(() -> sendWithRetry(json), EXECUTOR);
    }

    private String sendWithRetry(String json) {
        try {
            return sendOnce(json);
        } catch (AiException e) {
            boolean retryable = e.kind() == AiException.Kind.RATE_LIMITED
                    || e.kind() == AiException.Kind.OVERLOADED
                    || e.kind() == AiException.Kind.NETWORK;
            if (!retryable) {
                throw e;
            }
            sleep(RETRY_DELAY_MS);
            return sendOnce(json);
        }
    }

    private String sendOnce(String json) {
        HttpRequest.Builder request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(REQUEST_TIMEOUT)
                .header("content-type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (FALLBACK_MODELS.contains(model)) {
            request.header("anthropic-beta", FALLBACK_BETA);
        }
        HttpResponse<String> response;
        try {
            response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new AiException(AiException.Kind.NETWORK, e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException(AiException.Kind.NETWORK, "interrupted", e);
        }
        if (response.statusCode() != 200) {
            throw httpError(response.statusCode(), response.body());
        }
        return response.body();
    }

    private static AiException httpError(int status, String body) {
        String type = "";
        String message = "HTTP " + status;
        try {
            JsonObject error = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("error");
            type = error.get("type").getAsString();
            message = error.get("message").getAsString();
        } catch (RuntimeException ignored) {
            // body was not the documented error envelope; keep the status code only
        }
        CraftAdvisor.LOGGER.warn("Claude API error: status={} type={}", status, type);
        AiException.Kind kind = switch (status) {
            case 401, 403 -> AiException.Kind.BAD_KEY;
            case 429 -> AiException.Kind.RATE_LIMITED;
            case 500, 502, 503, 504, 529 -> AiException.Kind.OVERLOADED;
            default -> AiException.Kind.BAD_REQUEST;
        };
        return new AiException(kind, message);
    }

    /** Joins the text blocks; thinking and fallback blocks are skipped. */
    private static String textOf(String body) {
        String text = joinText(parse(body).getAsJsonArray("content"));
        if (text.isEmpty()) {
            throw new AiException(AiException.Kind.BAD_RESPONSE, "no text in response");
        }
        return text;
    }

    /** Text and tool_use blocks of a tool-loop response; the whole content array is kept to be sent back. */
    static AgentReply replyOf(String body) {
        JsonObject response = parse(body);
        JsonElement stop = response.get("stop_reason");
        if (stop != null && "max_tokens".equals(stop.getAsString())) {
            throw new AiException(AiException.Kind.BAD_RESPONSE, "answer was cut off at max_tokens");
        }
        JsonArray content = response.getAsJsonArray("content");
        List<ToolCall> calls = new ArrayList<>();
        for (JsonElement block : content) {
            JsonObject object = block.getAsJsonObject();
            if ("tool_use".equals(object.get("type").getAsString())) {
                calls.add(new ToolCall(object.get("id").getAsString(), object.get("name").getAsString(),
                        object.getAsJsonObject("input")));
            }
        }
        boolean wantsTools = stop != null && "tool_use".equals(stop.getAsString()) && !calls.isEmpty();
        return new AgentReply(new AgentMessage.Assistant(joinText(content), calls, content), wantsTools);
    }

    /** The response object with a content array; a refusal becomes {@link AiException.Kind#REFUSED}. */
    private static JsonObject parse(String body) {
        JsonObject response;
        try {
            response = JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new AiException(AiException.Kind.BAD_RESPONSE, "response was not JSON", e);
        }
        JsonElement stop = response.get("stop_reason");
        if (stop != null && "refusal".equals(stop.getAsString())) {
            throw new AiException(AiException.Kind.REFUSED, "refused");
        }
        if (!(response.get("content") instanceof JsonArray)) {
            throw new AiException(AiException.Kind.BAD_RESPONSE, "no content in response");
        }
        return response;
    }

    private static String joinText(JsonArray content) {
        StringBuilder text = new StringBuilder();
        for (JsonElement block : content) {
            JsonObject object = block.getAsJsonObject();
            if ("text".equals(object.get("type").getAsString())) {
                text.append(object.get("text").getAsString());
            }
        }
        return text.toString();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
