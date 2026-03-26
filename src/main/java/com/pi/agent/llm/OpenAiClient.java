package com.pi.agent.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pi.agent.AgentLoopConfig;
import com.pi.agent.event.AgentEvent;
import com.pi.agent.model.*;
import com.pi.agent.model.content.Content;
import com.pi.agent.model.content.ImageContent;
import com.pi.agent.model.content.TextContent;
import com.pi.agent.model.content.ToolCallContent;
import com.pi.agent.model.message.AgentMessage;
import com.pi.agent.model.message.AssistantMessage;
import com.pi.agent.model.message.ToolResultMessage;
import com.pi.agent.model.message.UserMessage;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.time.Duration;
import java.util.*;

/**
 * OpenAI-compatible LLM client for agent streaming.
 * Supports OpenAI, Anthropic, Google, and other OpenAI-compatible APIs.
 */
public class OpenAiClient {

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
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
            .build();
        this.objectMapper = new ObjectMapper();
        this.timeout = Duration.ofSeconds(120);
    }

    public OpenAiClient(String baseUrl, String apiKey) {
        this(WebClient.builder(), baseUrl, apiKey);
    }

    /**
     * Stream a chat completion request.
     * Returns a Flux of AgentEvent representing the streaming response.
     */
    public Flux<AgentEvent> streamCompletion(
        AgentState.ModelInfo model,
        AgentContext context,
        AgentLoopConfig config
    ) {
        return Flux.create(sink -> {
            try {
                ChatCompletionRequest request = buildRequest(model, context, config);
                String requestBody = objectMapper.writeValueAsString(request);

                webClient.post()
                    .uri(getApiPath(model))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToFlux(String.class)
                    .timeout(timeout)
                    .subscribe(
                        line -> parseStreamEvent(line, sink, model),
                        error -> {
                            sink.error(new RuntimeException("LLM streaming error: " + error.getMessage(), error));
                        },
                        () -> sink.complete()
                    );
            } catch (Exception e) {
                sink.error(new RuntimeException("Failed to build request: " + e.getMessage(), e));
            }
        });
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
        // Combine text content
        StringBuilder textBuilder = new StringBuilder();
        List<Map<String, Object>> toolCalls = new ArrayList<>();

        for (Content c : msg.content()) {
            if (c instanceof TextContent textContent) {
                textBuilder.append(textContent.text());
            } else if (c instanceof ToolCallContent toolCallContent) {
                toolCalls.add(Map.of(
                    "id", toolCallContent.id(),
                    "type", "function",
                    "function", Map.of(
                        "name", toolCallContent.name(),
                        "arguments", objectMapper.valueToTree(toolCallContent.arguments()).toString()
                    )
                ));
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
    private void parseStreamEvent(String line, FluxSink<AgentEvent> sink, AgentState.ModelInfo model) {
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
                    parseOpenAiChoice(event, sink);
                }
                // Anthropic-style response
                else if (event.has("type")) {
                    parseAnthropicEvent(event, sink);
                }
            } catch (Exception e) {
                // Ignore parse errors for malformed events
            }
        }
    }

    private void parseOpenAiChoice(JsonNode event, FluxSink<AgentEvent> sink) {
        JsonNode choices = event.get("choices");
        if (choices == null || choices.isEmpty()) {
            return;
        }

        JsonNode choice = choices.get(0);
        JsonNode delta = choice.get("delta");

        if (delta != null) {
            // Text content
            if (delta.has("content") && !delta.get("content").isNull()) {
                String content = delta.get("content").asText();
                // Would emit text delta event here
            }

            // Reasoning content
            for (String field : List.of("reasoning_content", "reasoning", "reasoning_text")) {
                if (delta.has(field) && !delta.get(field).isNull()) {
                    String reasoning = delta.get(field).asText();
                    // Would emit thinking delta event here
                    break;
                }
            }

            // Tool calls
            if (delta.has("tool_calls")) {
                for (JsonNode tc : delta.get("tool_calls")) {
                    // Would emit tool call event here
                }
            }
        }

        // Check finish reason
        if (choice.has("finish_reason") && !choice.get("finish_reason").isNull()) {
            String finishReason = choice.get("finish_reason").asText();
            // Map finish reason to stop reason
        }

        // Usage info
        if (event.has("usage")) {
            JsonNode usage = event.get("usage");
            // Extract usage statistics
        }
    }

    private void parseAnthropicEvent(JsonNode event, FluxSink<AgentEvent> sink) {
        String type = event.get("type").asText();

        switch (type) {
            case "content_block_start" -> {
                // Start of new content block
            }
            case "content_block_delta" -> {
                // Content block update
                JsonNode delta = event.get("delta");
                if (delta != null && delta.has("type")) {
                    String deltaType = delta.get("type").asText();
                    if ("text_delta".equals(deltaType) && delta.has("text")) {
                        String text = delta.get("text").asText();
                        // Emit text delta
                    } else if ("thinking_delta".equals(deltaType) && delta.has("thinking")) {
                        String thinking = delta.get("thinking").asText();
                        // Emit thinking delta
                    } else if ("input_json_delta".equals(deltaType) && delta.has("partial_json")) {
                        String partialJson = delta.get("partial_json").asText();
                        // Emit tool call delta
                    }
                }
            }
            case "content_block_stop" -> {
                // Content block complete
            }
            case "message_start" -> {
                // Message start
            }
            case "message_delta" -> {
                // Message update with stop reason
            }
            case "message_stop" -> {
                // Message complete
            }
            case "error" -> {
                JsonNode error = event.get("error");
                String message = error != null && error.has("message")
                    ? error.get("message").asText()
                    : "Unknown error";
                sink.error(new RuntimeException("LLM API error: " + message));
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
