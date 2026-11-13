package com.kuh.craftadvisor.ai;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * OpenAI Chat Completions ({@code POST {baseUrl}/chat/completions}): OpenAI itself, Ollama's {@code /v1} endpoint and
 * other compatible servers. Same prompts as Claude; the system prompt is a {@code system} message. The key, when
 * set, goes only in the Authorization header.
 */
public final class OpenAiCompatibleProvider implements AiProvider {
    private static final String LABEL = "OpenAI-compatible";
    private static final int ECHO_MAX_CHARS = 1000;
    private static final ItemPick QUESTION = new ItemPick("question", "", "");
    private static final Gson GSON = new Gson();

    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final AiHttp.Transport transport;

    /** {@code baseUri} is an allowed base URL ({@link ProviderSettings#checkBaseUrl}); {@code apiKey} may be empty. */
    OpenAiCompatibleProvider(URI baseUri, String apiKey, String model, int maxTokens, AiHttp.Transport transport) {
        this.endpoint = URI.create(baseUri + "/chat/completions");
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.transport = transport;
    }

    /**
     * A reply that is not the requested JSON is asked for once more, with the problem; a second bad reply is read
     * as a general question, so the free-question path takes over.
     */
    @Override
    public CompletableFuture<ItemPick> pickItem(OutgoingRequest request) {
        JsonObject body = pickBody(request);
        return post(body, true).thenCompose(response -> {
            String content = contentOf(message(response));
            try {
                return CompletableFuture.completedFuture(readPick(content));
            } catch (BadPick e) {
                return post(retryBody(body, content, e.getMessage()), true).thenApply(OpenAiCompatibleProvider::pickOrQuestion);
            }
        });
    }

    @Override
    public CompletableFuture<String> answer(OutgoingRequest request) {
        return post(answerBody(request), false).thenApply(OpenAiCompatibleProvider::textOf);
    }

    @Override
    public CompletableFuture<AgentReply> converse(List<AgentMessage> messages, List<ToolSpec> tools, boolean toolsEnabled) {
        return post(converseBody(messages, tools, toolsEnabled), !tools.isEmpty())
                .thenApply(OpenAiCompatibleProvider::replyOf);
    }

    JsonObject pickBody(OutgoingRequest request) {
        JsonObject body = baseBody(Prompts.PICK, messages(request));
        JsonObject schema = new JsonObject();
        schema.addProperty("name", "item_pick");
        schema.addProperty("strict", true);
        schema.add("schema", JsonParser.parseString("""
                {"type":"object","additionalProperties":false,"required":["intent","itemId","reason"],
                 "properties":{"intent":{"type":"string","enum":["craft","question"]},
                               "itemId":{"type":"string"},
                               "reason":{"type":"string"}}}""").getAsJsonObject());
        JsonObject format = new JsonObject();
        format.addProperty("type", "json_schema");
        format.add("json_schema", schema);
        body.add("response_format", format);
        return body;
    }

    JsonObject answerBody(OutgoingRequest request) {
        return baseBody(Prompts.ADVICE, messages(request));
    }

    JsonObject converseBody(List<AgentMessage> messages, List<ToolSpec> tools, boolean toolsEnabled) {
        JsonArray array = new JsonArray();
        messages.forEach(message -> addMessage(array, message));
        JsonObject body = baseBody(Prompts.CHAT, array);
        if (tools.isEmpty()) {
            return body;
        }
        JsonArray toolArray = new JsonArray();
        for (ToolSpec tool : tools) {
            JsonObject function = new JsonObject();
            function.addProperty("name", tool.name());
            function.addProperty("description", tool.description());
            function.add("parameters", tool.inputSchema());
            JsonObject json = new JsonObject();
            json.addProperty("type", "function");
            json.add("function", function);
            toolArray.add(json);
        }
        body.add("tools", toolArray);
        body.addProperty("tool_choice", toolsEnabled ? "auto" : "none");
        return body;
    }

    private JsonObject baseBody(String system, JsonArray messages) {
        JsonArray all = new JsonArray();
        all.add(message("system", system));
        all.addAll(messages);
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_completion_tokens", maxTokens);
        body.add("messages", all);
        return body;
    }

    private static JsonArray messages(OutgoingRequest request) {
        JsonArray array = new JsonArray();
        for (Turn turn : request.history()) {
            array.add(message(turn.fromPlayer() ? "user" : "assistant", turn.text()));
        }
        array.add(message("user", request.userText()));
        return array;
    }

    private static void addMessage(JsonArray array, AgentMessage message) {
        switch (message) {
            case AgentMessage.User user -> array.add(message("user", user.text()));
            case AgentMessage.Assistant assistant when assistant.providerRaw() != null -> array.add(assistant.providerRaw());
            case AgentMessage.Assistant assistant -> array.add(message("assistant", assistant.text()));
            case AgentMessage.ToolResults results -> results.results().forEach(result -> {
                JsonObject json = message("tool", result.isError() ? "Error: " + result.content() : result.content());
                json.addProperty("tool_call_id", result.callId());
                array.add(json);
            });
        }
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    /** The bad reply and what was wrong with it, appended to the same request. */
    private static JsonObject retryBody(JsonObject body, String badContent, String problem) {
        JsonObject retry = body.deepCopy();
        JsonArray messages = retry.getAsJsonArray("messages");
        if (!badContent.isBlank()) {
            String echo = badContent.length() > ECHO_MAX_CHARS ? badContent.substring(0, ECHO_MAX_CHARS) : badContent;
            messages.add(message("assistant", echo));
        }
        messages.add(message("user", Prompts.PICK_RETRY.formatted(problem)));
        return retry;
    }

    private CompletableFuture<String> post(JsonObject body, boolean structured) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");
        if (!apiKey.isBlank()) {
            headers.put("authorization", "Bearer " + apiKey);
        }
        return AiHttp.post(transport, new AiHttp.Call(LABEL, endpoint, headers, GSON.toJson(body), apiKey, structured));
    }

    private static ItemPick pickOrQuestion(String response) {
        try {
            return readPick(contentOf(message(response)));
        } catch (BadPick e) {
            return QUESTION;
        }
    }

    /** The item pick in {@code content}; a Markdown code fence around it is allowed (small local models add one). */
    static ItemPick readPick(String content) {
        String json = withoutCodeFence(content);
        JsonObject object;
        try {
            object = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new BadPick("the answer was not a JSON object");
        }
        String intent = stringField(object, "intent");
        if (!"craft".equals(intent) && !"question".equals(intent)) {
            throw new BadPick("\"intent\" must be \"craft\" or \"question\"");
        }
        String itemId = stringField(object, "itemId");
        if ("craft".equals(intent) && (itemId == null || itemId.isBlank())) {
            throw new BadPick("\"itemId\" must be an id from the candidate list when intent is \"craft\"");
        }
        String reason = stringField(object, "reason");
        return new ItemPick(intent, itemId == null ? "" : itemId, reason == null ? "" : reason);
    }

    private static String withoutCodeFence(String content) {
        String text = content.strip();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstLineEnd = text.indexOf('\n');
        int closing = text.lastIndexOf("```");
        return firstLineEnd < 0 || closing <= firstLineEnd ? text : text.substring(firstLineEnd + 1, closing).strip();
    }

    private static String stringField(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }

    /** The text answer. */
    static String textOf(String body) {
        String text = contentOf(message(body));
        if (text.isBlank()) {
            throw new AiException(AiException.Kind.BAD_RESPONSE, "no text in response");
        }
        return text;
    }

    /**
     * Text and tool calls. The message is kept to be sent back with only the fields requests accept (role, content,
     * tool_calls); arguments that are not a JSON object give a call with a null input, which the tool loop answers
     * with an error result.
     */
    static AgentReply replyOf(String body) {
        JsonObject message = message(body);
        String text = contentOf(message);
        List<ToolCall> calls = new ArrayList<>();
        JsonArray rawCalls = new JsonArray();
        if (message.get("tool_calls") instanceof JsonArray toolCalls) {
            for (int i = 0; i < toolCalls.size(); i++) {
                rawCalls.add(readCall(toolCalls.get(i), i, calls));
            }
        }
        JsonObject raw = new JsonObject();
        raw.addProperty("role", "assistant");
        raw.add("content", text.isEmpty() ? JsonNull.INSTANCE : message.get("content"));
        if (!rawCalls.isEmpty()) {
            raw.add("tool_calls", rawCalls);
        }
        return new AgentReply(new AgentMessage.Assistant(text, calls, raw), !calls.isEmpty());
    }

    /** Adds the call to {@code calls} and returns it in request form; a missing id becomes {@code call_<index>}. */
    private static JsonObject readCall(JsonElement element, int index, List<ToolCall> calls) {
        JsonObject function = element.isJsonObject() && element.getAsJsonObject().get("function") instanceof JsonObject f ? f : null;
        String name = function == null ? null : stringField(function, "name");
        if (name == null) {
            throw new AiException(AiException.Kind.BAD_RESPONSE, "tool call without a function name");
        }
        String id = stringField(element.getAsJsonObject(), "id");
        if (id == null || id.isBlank()) {
            id = "call_" + index;
        }
        JsonElement arguments = function.get("arguments");
        String argumentText = arguments == null || arguments.isJsonNull() ? ""
                : arguments.isJsonPrimitive() ? arguments.getAsString() : arguments.toString();
        calls.add(new ToolCall(id, name, argumentsOf(argumentText)));

        JsonObject rawFunction = new JsonObject();
        rawFunction.addProperty("name", name);
        rawFunction.addProperty("arguments", argumentText);
        JsonObject raw = new JsonObject();
        raw.addProperty("id", id);
        raw.addProperty("type", "function");
        raw.add("function", rawFunction);
        return raw;
    }

    /** The arguments object; empty text is no arguments; anything else that is not a JSON object is null. */
    private static JsonObject argumentsOf(String text) {
        if (text.isBlank()) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = JsonParser.parseString(text);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@code choices[0].message}; a cut-off or filtered answer and a refusal are errors. */
    private static JsonObject message(String body) {
        JsonObject choice;
        try {
            choice = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("choices").get(0).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new AiException(AiException.Kind.BAD_RESPONSE, "response had no choices", e);
        }
        String finish = stringField(choice, "finish_reason");
        if ("length".equals(finish)) {
            throw new AiException(AiException.Kind.BAD_RESPONSE, "answer was cut off at max tokens");
        }
        if ("content_filter".equals(finish)) {
            throw new AiException(AiException.Kind.REFUSED, "refused");
        }
        if (!(choice.get("message") instanceof JsonObject message)) {
            throw new AiException(AiException.Kind.BAD_RESPONSE, "response had no message");
        }
        String refusal = stringField(message, "refusal");
        if (refusal != null && !refusal.isBlank()) {
            throw new AiException(AiException.Kind.REFUSED, "refused");
        }
        return message;
    }

    /** The text content; null (a tool-call-only reply) is empty. */
    private static String contentOf(JsonObject message) {
        String content = stringField(message, "content");
        return content == null ? "" : content;
    }

    /** A pick reply that is not the requested JSON; the message says what to fix. */
    private static final class BadPick extends RuntimeException {
        BadPick(String message) {
            super(message);
        }
    }
}
