package com.pi.agent.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pi.agent.AgentLoopConfig;
import com.pi.agent.event.AgentEvent;
import com.pi.agent.event.AssistantMessageEvent;
import com.pi.agent.model.*;
import com.pi.agent.model.content.Content;
import com.pi.agent.model.content.ImageContent;
import com.pi.agent.model.content.TextContent;
import com.pi.agent.model.content.ToolCallContent;
import com.pi.agent.model.message.AgentMessage;
import com.pi.agent.model.message.AssistantMessage;
import com.pi.agent.model.message.ToolResultMessage;
import com.pi.agent.model.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenAI-compatible LLM client for agent streaming.
 * Supports OpenAI, Anthropic, Google, and other OpenAI-compatible APIs.
 */
public class OpenAiClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiClient.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final Duration timeout;
    private final String baseUrl;
    private final String apiKey;

    public OpenAiClient(WebClient.Builder webClientBuilder, String baseUrl, String apiKey) {
        this.baseUrl = baseUrl != null ? baseUrl : "https://api.openai.com";
        this.apiKey = apiKey;
        this.webClient = webClientBuilder
            .baseUrl(this.baseUrl)
            .defaultHeader("Content-Type", "application/json")
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
            .build();
        this.objectMapper = new ObjectMapper();
        this.timeout = Duration.ofSeconds(120);
    }

    public OpenAiClient(String baseUrl, String apiKey) {
        this(WebClient.builder(), baseUrl, apiKey);
    }

    /**
     * Get the Authorization header value.
     */
    private String getAuthHeader(AgentState.ModelInfo model) {
        String provider = model.provider().toLowerCase();
        if ("anthropic".equals(provider)) {
            return "x-api-key"; // Anthropic uses x-api-key header
        }
        return "Authorization"; // OpenAI and others use Bearer token
    }

    /**
     * Stream a chat completion request.
     * Returns a Flux of AssistantMessageEvent representing the streaming response.
     */
    public Flux<AssistantMessageEvent> streamCompletion(
        AgentState.ModelInfo model,
        AgentContext context,
        AgentLoopConfig config
    ) {
        return Flux.create(sink -> {
            try {
                ChatCompletionRequest request = buildRequest(model, context, config);
                String requestBody = objectMapper.writeValueAsString(request);

                String authHeader = getAuthHeader(model);
                String authValue = "anthropic".equalsIgnoreCase(model.provider())
                    ? apiKey
                    : "Bearer " + apiKey;

                WebClient.RequestHeadersSpec<?> requestSpec = webClient.post()
                    .uri(getApiPath(model))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .header(authHeader, authValue)
                    .bodyValue(requestBody);

                // Add Anthropic-specific headers
                if ("anthropic".equalsIgnoreCase(model.provider())) {
                    requestSpec.header("anthropic-version", "2023-06-01");
                }

                StreamState state = new StreamState(model);

                requestSpec.retrieve()
                    .bodyToFlux(String.class)
                    .timeout(timeout)
                    .subscribe(
                        line -> parseStreamEvent(line, sink, state),
                        error -> {
                            log.error("LLM streaming error: {}", error.getMessage());
                            sink.next(new AssistantMessageEvent.Error(
                                "LLM streaming error: " + error.getMessage(),
                                error
                            ));
                            sink.complete();
                        },
                        () -> {
                            // Finalize the message
                            AssistantMessage finalMessage = state.finalizeMessage();
                            sink.next(new AssistantMessageEvent.Done(finalMessage));
                            sink.complete();
                        }
                    );
            } catch (Exception e) {
                log.error("Failed to build request: {}", e.getMessage(), e);
                sink.next(new AssistantMessageEvent.Error(
                    "Failed to build request: " + e.getMessage(),
                    e
                ));
                sink.complete();
            }
        });
    }

    /**
     * Stream state to track message construction.
     */
    private class StreamState {
        private final AgentState.ModelInfo model;
        private final List<Content> content = new ArrayList<>();
        private final Map<Integer, StringBuilder> textBuilders = new ConcurrentHashMap<>();
        private final Map<Integer, StringBuilder> thinkingBuilders = new ConcurrentHashMap<>();
        private final Map<Integer, ToolCallBuilder> toolCallBuilders = new ConcurrentHashMap<>();
        private final AtomicReference<StopReason> stopReason = new AtomicReference<>(StopReason.END_TURN);
        private final AtomicReference<String> errorMessage = new AtomicReference<>();
        private final AtomicReference<AssistantMessage.Usage> usage = new AtomicReference<>(AssistantMessage.Usage.empty());
        private final AtomicInteger currentTextIndex = new AtomicInteger(-1);
        private final AtomicInteger currentThinkingIndex = new AtomicInteger(-1);
        private final AtomicInteger currentToolCallIndex = new AtomicInteger(-1);
        private volatile boolean started = false;

        StreamState(AgentState.ModelInfo model) {
            this.model = model;
        }

        void ensureStarted(FluxSink<AssistantMessageEvent> sink) {
            if (!started) {
                started = true;
                AssistantMessage partial = buildPartial();
                sink.next(new AssistantMessageEvent.Start(partial));
            }
        }

        AssistantMessage buildPartial() {
            List<Content> allContent = new ArrayList<>();
            allContent.addAll(content);
            return new AssistantMessage(
                "assistant",
                allContent,
                null,
                model.provider(),
                model.id(),
                usage.get(),
                stopReason.get(),
                errorMessage.get(),
                System.currentTimeMillis()
            );
        }

        AssistantMessage finalizeMessage() {
            // Finalize any pending text content
            for (Map.Entry<Integer, StringBuilder> entry : textBuilders.entrySet()) {
                if (entry.getKey() >= content.size()) {
                    content.add(new TextContent(entry.getValue().toString()));
                }
            }

            // Finalize any pending thinking content
            for (Map.Entry<Integer, StringBuilder> entry : thinkingBuilders.entrySet()) {
                if (entry.getKey() >= content.size()) {
                    content.add(new TextContent(entry.getValue().toString())); // Treat as text for now
                }
            }

            // Finalize any pending tool calls
            for (Map.Entry<Integer, ToolCallBuilder> entry : toolCallBuilders.entrySet()) {
                if (entry.getKey() >= content.size()) {
                    ToolCallBuilder tcb = entry.getValue();
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> args = tcb.arguments.length() > 0
                            ? objectMapper.readValue(tcb.arguments.toString(), Map.class)
                            : Map.of();
                        content.add(new ToolCallContent(tcb.id, tcb.name, args));
                    } catch (JsonProcessingException e) {
                        log.warn("Failed to parse tool call arguments: {}", e.getMessage());
                    }
                }
            }

            return new AssistantMessage(
                "assistant",
                content,
                null,
                model.provider(),
                model.id(),
                usage.get(),
                stopReason.get(),
                errorMessage.get(),
                System.currentTimeMillis()
            );
        }

        void appendText(int index, String delta, FluxSink<AssistantMessageEvent> sink) {
            ensureStarted(sink);
            if (!textBuilders.containsKey(index)) {
                textBuilders.put(index, new StringBuilder());
                sink.next(new AssistantMessageEvent.TextStart(index));
            }
            textBuilders.get(index).append(delta);
            sink.next(new AssistantMessageEvent.TextDelta(index, delta));
        }

        void appendThinking(int index, String delta, FluxSink<AssistantMessageEvent> sink) {
            ensureStarted(sink);
            if (!thinkingBuilders.containsKey(index)) {
                thinkingBuilders.put(index, new StringBuilder());
                sink.next(new AssistantMessageEvent.ThinkingStart(index));
            }
            thinkingBuilders.get(index).append(delta);
            sink.next(new AssistantMessageEvent.ThinkingDelta(index, delta));
        }

        void appendToolCallDelta(int index, String id, String name, String argsDelta, FluxSink<AssistantMessageEvent> sink) {
            ensureStarted(sink);
            ToolCallBuilder tcb = toolCallBuilders.computeIfAbsent(index, i -> new ToolCallBuilder());
            if (id != null) tcb.id = id;
            if (name != null) tcb.name = name;
            if (argsDelta != null) {
                tcb.arguments.append(argsDelta);
                sink.next(new AssistantMessageEvent.ToolCallDelta(index, tcb.id, tcb.name, argsDelta));
            }
        }

        void startToolCall(int index, String id, String name, FluxSink<AssistantMessageEvent> sink) {
            ensureStarted(sink);
            ToolCallBuilder tcb = toolCallBuilders.computeIfAbsent(index, i -> new ToolCallBuilder());
            tcb.id = id;
            tcb.name = name;
            sink.next(new AssistantMessageEvent.ToolCallStart(index, id, name));
        }

        void setStopReason(StopReason reason) {
            stopReason.set(reason);
        }

        void setErrorMessage(String msg) {
            errorMessage.set(msg);
        }

        void setUsage(long input, long output) {
            usage.set(new AssistantMessage.Usage(input, output, 0, 0, input + output, AssistantMessage.Usage.Cost.empty()));
        }
    }

    private static class ToolCallBuilder {
        String id;
        String name;
        StringBuilder arguments = new StringBuilder();
    }

    /**
     * Get the API endpoint based on provider.
     */
    private String getApiPath(AgentState.ModelInfo model) {
        String provider = model.provider().toLowerCase();
        return switch (provider) {
            case "openai", "zai", "openrouter" -> "/v1/chat/completions";
            case "google" -> "/v1beta/openai/chat/completions";
            case "anthropic" -> "/v1/messages";
            default -> "/v1/chat/completions";
        };
    }

    /**
     * Build the chat completion request.
     */
    private ChatCompletionRequest buildRequest(
        AgentState.ModelInfo model,
        AgentContext context,
        AgentLoopConfig config
    ) {
        List<ChatCompletionMessage> messages = new ArrayList<>();

        // Add system prompt if present
        if (context.systemPrompt() != null && !context.systemPrompt().isBlank()) {
            messages.add(new ChatCompletionSystemMessage(context.systemPrompt()));
        }

        // Convert agent messages to LLM format
        for (AgentMessage msg : context.messages()) {
            if (msg instanceof UserMessage userMsg) {
                messages.add(convertUserMessage(userMsg));
            } else if (msg instanceof AssistantMessage assistantMsg) {
                messages.add(convertAssistantMessage(assistantMsg));
            } else if (msg instanceof ToolResultMessage toolResultMsg) {
                messages.add(convertToolResultMessage(toolResultMsg));
            }
        }

        List<ToolDefinition> tools = buildTools(context.tools());

        return new ChatCompletionRequest(
            model.id(),
            messages,
            tools,
            config.maxTokens(),
            config.temperature(),
            true // stream
        );
    }

    private ChatCompletionUserMessage convertUserMessage(UserMessage msg) {
        List<Content> content = msg.content();
        if (content.size() == 1 && content.get(0) instanceof TextContent textContent) {
            return new ChatCompletionUserMessage(textContent.text());
        }
        // Convert multi-part content
        List<Map<String, Object>> parts = new ArrayList<>();
        for (Content c : content) {
            if (c instanceof TextContent textContent) {
                parts.add(Map.of("type", "text", "text", textContent.text()));
            } else if (c instanceof ImageContent imageContent) {
                String imageUrl = imageContent.url();
                if (imageUrl == null && imageContent.base64() != null) {
                    imageUrl = "data:" + imageContent.mimeType() + ";base64," + imageContent.base64();
                }
                if (imageUrl != null) {
                    parts.add(Map.of(
                        "type", "image_url",
                        "image_url", Map.of("url", imageUrl)
                    ));
                }
            }
        }
        return new ChatCompletionUserMessage(parts);
    }

    private ChatCompletionAssistantMessage convertAssistantMessage(AssistantMessage msg) {
        StringBuilder textBuilder = new StringBuilder();
        List<Map<String, Object>> toolCalls = new ArrayList<>();

        for (Content c : msg.content()) {
            if (c instanceof TextContent textContent) {
                textBuilder.append(textContent.text());
            } else if (c instanceof ToolCallContent toolCallContent) {
                try {
                    toolCalls.add(Map.of(
                        "id", toolCallContent.id(),
                        "type", "function",
                        "function", Map.of(
                            "name", toolCallContent.name(),
                            "arguments", objectMapper.writeValueAsString(toolCallContent.arguments())
                        )
                    ));
                } catch (JsonProcessingException e) {
                    log.warn("Failed to serialize tool call arguments: {}", e.getMessage());
                }
            }
        }

        String textContent = textBuilder.toString().isEmpty() ? null : textBuilder.toString();
        List<Map<String, Object>> toolCallsList = toolCalls.isEmpty() ? null : toolCalls;
        return new ChatCompletionAssistantMessage(textContent, toolCallsList);
    }

    private ChatCompletionToolMessage convertToolResultMessage(ToolResultMessage msg) {
        StringBuilder textBuilder = new StringBuilder();
        for (Content c : msg.content()) {
            if (c instanceof TextContent textContent) {
                textBuilder.append(textContent.text());
            }
        }
        return new ChatCompletionToolMessage(msg.toolCallId(), textBuilder.toString());
    }

    private List<ToolDefinition> buildTools(List<AgentTool<?>> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }

        return tools.stream()
            .map(tool -> new ToolDefinition(
                "function",
                tool.name(),
                tool.description(),
                tool.parameters()
            ))
            .toList();
    }

    /**
     * Parse SSE stream events.
     */
    private void parseStreamEvent(String line, FluxSink<AssistantMessageEvent> sink, StreamState state) {
        if (line == null || line.isBlank()) {
            return;
        }

        if (line.startsWith("data: ")) {
            String data = line.substring(6).trim();
            if (data.isEmpty() || data.equals("[DONE]")) {
                return;
            }

            try {
                JsonNode event = objectMapper.readTree(data);

                // OpenAI-style response
                if (event.has("choices")) {
                    parseOpenAiChoice(event, sink, state);
                }
                // Anthropic-style response
                else if (event.has("type")) {
                    parseAnthropicEvent(event, sink, state);
                }
            } catch (Exception e) {
                log.debug("Failed to parse stream event: {}", e.getMessage());
            }
        }
        // Anthropic-style without "data: " prefix
        else if (line.startsWith("{")) {
            try {
                JsonNode event = objectMapper.readTree(line);
                if (event.has("type")) {
                    parseAnthropicEvent(event, sink, state);
                }
            } catch (Exception e) {
                log.debug("Failed to parse stream event: {}", e.getMessage());
            }
        }
    }

    private void parseOpenAiChoice(JsonNode event, FluxSink<AssistantMessageEvent> sink, StreamState state) {
        JsonNode choices = event.get("choices");
        if (choices == null || choices.isEmpty()) {
            return;
        }

        JsonNode choice = choices.get(0);
        JsonNode delta = choice.get("delta");
        int index = choice.has("index") ? choice.get("index").asInt() : 0;

        if (delta != null) {
            // Text content
            if (delta.has("content") && !delta.get("content").isNull()) {
                String content = delta.get("content").asText();
                state.appendText(index, content, sink);
            }

            // Reasoning content (for models that support it)
            for (String field : List.of("reasoning_content", "reasoning", "reasoning_text")) {
                if (delta.has(field) && !delta.get(field).isNull()) {
                    String reasoning = delta.get(field).asText();
                    state.appendThinking(index, reasoning, sink);
                    break;
                }
            }

            // Tool calls
            if (delta.has("tool_calls")) {
                for (JsonNode tc : delta.get("tool_calls")) {
                    int tcIndex = tc.has("index") ? tc.get("index").asInt() : index;
                    String id = tc.has("id") ? tc.get("id").asText() : null;
                    
                    JsonNode function = tc.get("function");
                    String name = null;
                    String argsDelta = null;
                    
                    if (function != null) {
                        if (function.has("name")) {
                            name = function.get("name").asText();
                        }
                        if (function.has("arguments")) {
                            argsDelta = function.get("arguments").asText();
                        }
                    }
                    
                    if (id != null && name != null) {
                        state.startToolCall(tcIndex, id, name, sink);
                    }
                    if (argsDelta != null) {
                        state.appendToolCallDelta(tcIndex, id, name, argsDelta, sink);
                    }
                }
            }
        }

        // Check finish reason
        if (choice.has("finish_reason") && !choice.get("finish_reason").isNull()) {
            String finishReason = choice.get("finish_reason").asText();
            StopReason stopReason = switch (finishReason) {
                case "stop" -> StopReason.END_TURN;
                case "tool_calls" -> StopReason.TOOL_USE;
                case "length" -> StopReason.MAX_TOKENS;
                case "content_filter" -> StopReason.ERROR;
                default -> StopReason.END_TURN;
            };
            state.setStopReason(stopReason);
        }

        // Usage info (streaming usage)
        if (event.has("usage")) {
            JsonNode usage = event.get("usage");
            long input = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asLong() : 0;
            long output = usage.has("completion_tokens") ? usage.get("completion_tokens").asLong() : 0;
            state.setUsage(input, output);
        }
    }

    private void parseAnthropicEvent(JsonNode event, FluxSink<AssistantMessageEvent> sink, StreamState state) {
        String type = event.get("type").asText();

        switch (type) {
            case "message_start" -> {
                JsonNode message = event.get("message");
                if (message != null && message.has("usage")) {
                    JsonNode usage = message.get("usage");
                    long input = usage.has("input_tokens") ? usage.get("input_tokens").asLong() : 0;
                    state.setUsage(input, 0);
                }
            }
            case "content_block_start" -> {
                int index = event.has("index") ? event.get("index").asInt() : 0;
                JsonNode contentBlock = event.get("content_block");
                if (contentBlock != null) {
                    String blockType = contentBlock.has("type") ? contentBlock.get("type").asText() : "";
                    if ("text".equals(blockType)) {
                        state.ensureStarted(sink);
                        sink.next(new AssistantMessageEvent.TextStart(index));
                    } else if ("thinking".equals(blockType)) {
                        state.ensureStarted(sink);
                        sink.next(new AssistantMessageEvent.ThinkingStart(index));
                    } else if ("tool_use".equals(blockType)) {
                        String id = contentBlock.has("id") ? contentBlock.get("id").asText() : UUID.randomUUID().toString();
                        String name = contentBlock.has("name") ? contentBlock.get("name").asText() : "unknown";
                        state.startToolCall(index, id, name, sink);
                    }
                }
            }
            case "content_block_delta" -> {
                int index = event.has("index") ? event.get("index").asInt() : 0;
                JsonNode delta = event.get("delta");
                if (delta != null && delta.has("type")) {
                    String deltaType = delta.get("type").asText();
                    if ("text_delta".equals(deltaType) && delta.has("text")) {
                        String text = delta.get("text").asText();
                        state.appendText(index, text, sink);
                    } else if ("thinking_delta".equals(deltaType) && delta.has("thinking")) {
                        String thinking = delta.get("thinking").asText();
                        state.appendThinking(index, thinking, sink);
                    } else if ("input_json_delta".equals(deltaType) && delta.has("partial_json")) {
                        String partialJson = delta.get("partial_json").asText();
                        state.appendToolCallDelta(index, null, null, partialJson, sink);
                    }
                }
            }
            case "content_block_stop" -> {
                int index = event.has("index") ? event.get("index").asInt() : 0;
                // Could emit TextEnd/ThinkingEnd/ToolCallEnd here
            }
            case "message_delta" -> {
                JsonNode delta = event.get("delta");
                if (delta != null && delta.has("stop_reason")) {
                    String stopReasonStr = delta.get("stop_reason").asText();
                    StopReason stopReason = switch (stopReasonStr) {
                        case "end_turn" -> StopReason.END_TURN;
                        case "tool_use" -> StopReason.TOOL_USE;
                        case "max_tokens" -> StopReason.MAX_TOKENS;
                        case "stop_sequence" -> StopReason.STOP_SEQUENCE;
                        default -> StopReason.END_TURN;
                    };
                    state.setStopReason(stopReason);
                }
                JsonNode usage = event.get("usage");
                if (usage != null) {
                    long output = usage.has("output_tokens") ? usage.get("output_tokens").asLong() : 0;
                    // Update output tokens
                }
            }
            case "message_stop" -> {
                // Message complete
            }
            case "error" -> {
                JsonNode error = event.get("error");
                String message = error != null && error.has("message")
                    ? error.get("message").asText()
                    : "Unknown error";
                state.setErrorMessage(message);
                state.setStopReason(StopReason.ERROR);
                sink.next(new AssistantMessageEvent.Error(message));
            }
            default -> {
                // Unknown event type, ignore
            }
        }
    }

    // DTOs for OpenAI API

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChatCompletionRequest(
        String model,
        List<ChatCompletionMessage> messages,
        List<ToolDefinition> tools,
        Integer max_tokens,
        Double temperature,
        boolean stream
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public sealed interface ChatCompletionMessage permits
        ChatCompletionSystemMessage,
        ChatCompletionUserMessage,
        ChatCompletionAssistantMessage,
        ChatCompletionToolMessage {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatCompletionSystemMessage(String role, String content)
        implements ChatCompletionMessage {
        public ChatCompletionSystemMessage(String content) {
            this("system", content);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatCompletionUserMessage(String role, Object content)
        implements ChatCompletionMessage {
        public ChatCompletionUserMessage(String text) {
            this("user", text);
        }
        public ChatCompletionUserMessage(List<Map<String, Object>> parts) {
            this("user", parts);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatCompletionAssistantMessage(
        String role,
        Object content,
        List<Map<String, Object>> tool_calls
    ) implements ChatCompletionMessage {
        public ChatCompletionAssistantMessage(String content, List<Map<String, Object>> toolCalls) {
            this("assistant", content, toolCalls);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatCompletionToolMessage(
        String role,
        String content,
        String tool_call_id
    ) implements ChatCompletionMessage {
        public ChatCompletionToolMessage(String toolCallId, String content) {
            this("tool", content, toolCallId);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolDefinition(
        String type,
        String name,
        String description,
        Object parameters
    ) {}
}
