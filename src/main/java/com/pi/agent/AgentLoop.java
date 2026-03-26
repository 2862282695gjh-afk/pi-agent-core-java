package com.pi.agent;

import com.pi.agent.event.AgentEvent;
import com.pi.agent.event.AssistantMessageEvent;
import com.pi.agent.llm.OpenAiClient;
import com.pi.agent.model.*;
import com.pi.agent.model.content.Content;
import com.pi.agent.model.content.TextContent;
import com.pi.agent.model.content.ToolCallContent;
import com.pi.agent.model.hook.AfterToolCallContext;
import com.pi.agent.model.hook.AfterToolCallResult;
import com.pi.agent.model.hook.BeforeToolCallContext;
import com.pi.agent.model.hook.BeforeToolCallResult;
import com.pi.agent.model.message.AgentMessage;
import com.pi.agent.model.message.AssistantMessage;
import com.pi.agent.model.message.ToolResultMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Agent loop implementation using Project Reactor.
 * Integrates with OpenAiClient for LLM streaming.
 */
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final AgentContext context;
    private final AgentLoopConfig config;
    private final Consumer<AgentEvent> eventSink;
    private final OpenAiClient llmClient;

    public AgentLoop(AgentContext context, AgentLoopConfig config, Consumer<AgentEvent> eventSink) {
        this(context, config, eventSink, null);
    }

    public AgentLoop(AgentContext context, AgentLoopConfig config, Consumer<AgentEvent> eventSink, OpenAiClient llmClient) {
        this.context = context;
        this.config = config;
        this.eventSink = eventSink;
        this.llmClient = llmClient;
    }

    /**
     * Run the agent loop with new prompt messages.
     */
    public Flux<AgentMessage> run(List<AgentMessage> prompts) {
        List<AgentMessage> newMessages = new ArrayList<>(prompts);
        List<AgentMessage> contextMessages = new ArrayList<>(context.messages());
        contextMessages.addAll(prompts);
        AgentContext currentContext = context.withMessages(contextMessages);

        return Flux.create(sink -> {
            Consumer<AgentEvent> emit = event -> {
                eventSink.accept(event);
                sink.next(event);
            };

            emit.accept(new AgentEvent.AgentStart());
            emit.accept(new AgentEvent.TurnStart());

            for (AgentMessage prompt : prompts) {
                emit.accept(new AgentEvent.MessageStart(prompt));
                emit.accept(new AgentEvent.MessageEnd(prompt));
            }

            runLoop(currentContext, newMessages, emit)
                .doOnTerminate(() -> {
                    emit.accept(new AgentEvent.AgentEnd(newMessages));
                    sink.complete();
                })
                .subscribe(
                    v -> {},
                    e -> {
                        log.error("Agent loop error: {}", e.getMessage(), e);
                        sink.error(e);
                    }
                );
        }).thenMany(Flux.fromIterable(newMessages));
    }

    /**
     * Main loop logic - returns Mono<Void> for side effects.
     */
    private Mono<Void> runLoop(
        AgentContext currentContext,
        List<AgentMessage> newMessages,
        Consumer<AgentEvent> emit
    ) {
        return Mono.defer(() -> {
            boolean firstTurn = true;
            List<AgentMessage> pendingMessages = config.getSteeringMessages() != null
                ? config.getSteeringMessages().get()
                : List.of();

            return runOuterLoop(currentContext, newMessages, pendingMessages, firstTurn, emit);
        });
    }

    private Mono<Void> runOuterLoop(
        AgentContext currentContext,
        List<AgentMessage> newMessages,
        List<AgentMessage> pendingMessages,
        boolean firstTurn,
        Consumer<AgentEvent> emit
    ) {
        return Mono.defer(() -> {
            if (pendingMessages.isEmpty()) {
                List<AgentMessage> followUp = config.getFollowUpMessages() != null
                    ? config.getFollowUpMessages().get()
                    : List.of();

                if (!followUp.isEmpty()) {
                    return runOuterLoop(currentContext, newMessages, followUp, firstTurn, emit);
                }
                return Mono.empty();
            }

            return runInnerLoop(currentContext, newMessages, pendingMessages, firstTurn, emit)
                .flatMap(newPending -> runOuterLoop(currentContext, newMessages, newPending, false, emit));
        });
    }

    private Mono<List<AgentMessage>> runInnerLoop(
        AgentContext currentContext,
        List<AgentMessage> newMessages,
        List<AgentMessage> pendingMessages,
        boolean firstTurn,
        Consumer<AgentEvent> emit
    ) {
        return Mono.defer(() -> {
            if (!firstTurn) {
                emit.accept(new AgentEvent.TurnStart());
            }

            // Process pending messages
            for (AgentMessage message : pendingMessages) {
                emit.accept(new AgentEvent.MessageStart(message));
                currentContext.messages().add(message);
                newMessages.add(message);
                emit.accept(new AgentEvent.MessageEnd(message));
            }

            // Stream assistant response from LLM
            return streamAssistantResponse(currentContext, newMessages, emit)
                .flatMap(message -> {
                    if (message.hasError()) {
                        emit.accept(new AgentEvent.TurnEnd(message, List.of()));
                        return Mono.just(List.of());
                    }

                    List<ToolCallContent> toolCalls = message.content().stream()
                        .filter(c -> c instanceof ToolCallContent)
                        .map(c -> (ToolCallContent) c)
                        .toList();

                    if (toolCalls.isEmpty()) {
                        emit.accept(new AgentEvent.TurnEnd(message, List.of()));
                        List<AgentMessage> steering = config.getSteeringMessages() != null
                            ? config.getSteeringMessages().get()
                            : List.of();
                        return Mono.just(steering);
                    }

                    return executeToolCalls(currentContext, message, toolCalls, emit)
                        .map(toolResults -> {
                            emit.accept(new AgentEvent.TurnEnd(message, toolResults));
                            List<AgentMessage> steering = config.getSteeringMessages() != null
                                ? config.getSteeringMessages().get()
                                : List.of();
                            return steering;
                        });
                });
        });
    }

    /**
     * Stream assistant response from LLM.
     */
    private Mono<AssistantMessage> streamAssistantResponse(
        AgentContext currentContext,
        List<AgentMessage> newMessages,
        Consumer<AgentEvent> emit
    ) {
        // If no LLM client, return placeholder
        if (llmClient == null) {
            log.warn("No LLM client configured, returning placeholder response");
            AssistantMessage placeholder = new AssistantMessage(
                List.of(new TextContent("LLM client not configured")),
                "placeholder",
                "none"
            );
            emit.accept(new AgentEvent.MessageStart(placeholder));
            newMessages.add(placeholder);
            emit.accept(new AgentEvent.MessageEnd(placeholder));
            return Mono.just(placeholder);
        }

        // Build context for LLM call
        AgentContext llmContext = currentContext;

        // Apply context transform if configured
        if (config.transformContext() != null) {
            return config.transformContext()
                .apply(currentContext.messages(), null)
                .flatMap(transformed -> {
                    AgentContext transformedContext = currentContext.withMessages(transformed);
                    return doStreamAssistantResponse(transformedContext, newMessages, emit);
                })
                .onErrorResume(e -> {
                    log.error("Context transform failed: {}", e.getMessage(), e);
                    return doStreamAssistantResponse(currentContext, newMessages, emit);
                });
        }

        return doStreamAssistantResponse(llmContext, newMessages, emit);
    }

    private Mono<AssistantMessage> doStreamAssistantResponse(
        AgentContext llmContext,
        List<AgentMessage> newMessages,
        Consumer<AgentEvent> emit
    ) {
        AtomicReference<AssistantMessage> partialRef = new AtomicReference<>();
        AtomicReference<Boolean> addedPartial = new AtomicReference<>(false);

        return llmClient.streamCompletion(config.model(), llmContext, config)
            .doOnNext(event -> {
                switch (event) {
                    case AssistantMessageEvent.Start start -> {
                        partialRef.set(start.partial());
                        llmContext.messages().add(start.partial());
                        addedPartial.set(true);
                        emit.accept(new AgentEvent.MessageStart(start.partial()));
                    }
                    case AssistantMessageEvent.TextStart ts -> {
                        // Already handled in Start
                    }
                    case AssistantMessageEvent.TextDelta td -> {
                        if (partialRef.get() != null) {
                            emit.accept(new AgentEvent.MessageUpdate(partialRef.get(), td));
                        }
                    }
                    case AssistantMessageEvent.TextEnd te -> {
                        // Text block complete
                    }
                    case AssistantMessageEvent.ThinkingStart ts -> {
                        // Thinking block start
                    }
                    case AssistantMessageEvent.ThinkingDelta td -> {
                        if (partialRef.get() != null) {
                            emit.accept(new AgentEvent.MessageUpdate(partialRef.get(), td));
                        }
                    }
                    case AssistantMessageEvent.ThinkingEnd te -> {
                        // Thinking block complete
                    }
                    case AssistantMessageEvent.ToolCallStart tcs -> {
                        if (partialRef.get() != null) {
                            emit.accept(new AgentEvent.MessageUpdate(partialRef.get(), tcs));
                        }
                    }
                    case AssistantMessageEvent.ToolCallDelta tcd -> {
                        if (partialRef.get() != null) {
                            emit.accept(new AgentEvent.MessageUpdate(partialRef.get(), tcd));
                        }
                    }
                    case AssistantMessageEvent.ToolCallEnd tce -> {
                        // Tool call complete
                    }
                    case AssistantMessageEvent.Done done -> {
                        AssistantMessage finalMessage = done.message();
                        if (addedPartial.get()) {
                            // Replace partial with final
                            int lastIndex = llmContext.messages().size() - 1;
                            if (lastIndex >= 0) {
                                llmContext.messages().set(lastIndex, finalMessage);
                            }
                        } else {
                            llmContext.messages().add(finalMessage);
                            emit.accept(new AgentEvent.MessageStart(finalMessage));
                        }
                        newMessages.add(finalMessage);
                        emit.accept(new AgentEvent.MessageEnd(finalMessage));
                        partialRef.set(finalMessage);
                    }
                    case AssistantMessageEvent.Error error -> {
                        log.error("LLM stream error: {}", error.message());
                        AssistantMessage errorMsg = new AssistantMessage(
                            List.of(new TextContent("Error: " + error.message())),
                            config.model().provider(),
                            config.model().id(),
                            AssistantMessage.Usage.empty(),
                            StopReason.ERROR,
                            error.message(),
                            System.currentTimeMillis()
                        );
                        newMessages.add(errorMsg);
                        emit.accept(new AgentEvent.MessageStart(errorMsg));
                        emit.accept(new AgentEvent.MessageEnd(errorMsg));
                        partialRef.set(errorMsg);
                    }
                }
            })
            .then(Mono.fromSupplier(() -> {
                AssistantMessage result = partialRef.get();
                if (result == null) {
                    // No events received, return empty message
                    result = new AssistantMessage(
                        List.of(),
                        config.model().provider(),
                        config.model().id(),
                        AssistantMessage.Usage.empty(),
                        StopReason.ERROR,
                        "No response received",
                        System.currentTimeMillis()
                    );
                    newMessages.add(result);
                    emit.accept(new AgentEvent.MessageStart(result));
                    emit.accept(new AgentEvent.MessageEnd(result));
                }
                return result;
            }));
    }

    private Mono<List<ToolResultMessage>> executeToolCalls(
        AgentContext currentContext,
        AssistantMessage message,
        List<ToolCallContent> toolCalls,
        Consumer<AgentEvent> emit
    ) {
        List<ToolResultMessage> results = new ArrayList<>();

        if (config.toolExecution() == ToolExecutionMode.SEQUENTIAL) {
            return Flux.fromIterable(toolCalls)
                .concatMap(toolCall -> executeToolCall(currentContext, message, toolCall, emit)
                    .doOnNext(results::add))
                .then(Mono.just(results));
        } else {
            // Parallel execution
            return Flux.fromIterable(toolCalls)
                .flatMap(toolCall -> executeToolCall(currentContext, message, toolCall, emit)
                    .doOnNext(results::add))
                .then(Mono.just(results));
        }
    }

    private Mono<ToolResultMessage> executeToolCall(
        AgentContext currentContext,
        AssistantMessage assistantMessage,
        ToolCallContent toolCall,
        Consumer<AgentEvent> emit
    ) {
        return Mono.defer(() -> {
            emit.accept(new AgentEvent.ToolExecutionStart(
                toolCall.id(),
                toolCall.name(),
                toolCall.arguments()
            ));

            AgentTool<?> tool = currentContext.tools() != null
                ? currentContext.tools().stream()
                    .filter(t -> t.name().equals(toolCall.name()))
                    .findFirst()
                    .orElse(null)
                : null;

            if (tool == null) {
                ToolResultMessage result = ToolResultMessage.error(
                    toolCall.id(),
                    toolCall.name(),
                    "Tool " + toolCall.name() + " not found"
                );
                return Mono.just(finalizeToolResult(toolCall, result, true, emit));
            }

            // Execute beforeToolCall hook if configured
            if (config.beforeToolCall() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> args = (Map<String, Object>) toolCall.arguments();
                BeforeToolCallContext hookContext = new BeforeToolCallContext(
                    assistantMessage,
                    toolCall,
                    args,
                    currentContext
                );

                return config.beforeToolCall()
                    .apply(hookContext, null)
                    .flatMap(hookResult -> {
                        if (hookResult != null && hookResult.blocked()) {
                            String reason = hookResult.reason() != null
                                ? hookResult.reason()
                                : "Tool blocked by beforeToolCall hook";
                            ToolResultMessage blockedResult = ToolResultMessage.error(
                                toolCall.id(),
                                toolCall.name(),
                                reason
                            );
                            return Mono.just(finalizeToolResult(toolCall, blockedResult, true, emit));
                        }
                        return doExecuteToolCall(currentContext, assistantMessage, toolCall, tool, emit);
                    })
                    .onErrorResume(e -> {
                        log.error("beforeToolCall hook error: {}", e.getMessage(), e);
                        return doExecuteToolCall(currentContext, assistantMessage, toolCall, tool, emit);
                    });
            }

            return doExecuteToolCall(currentContext, assistantMessage, toolCall, tool, emit);
        });
    }

    @SuppressWarnings("unchecked")
    private Mono<ToolResultMessage> doExecuteToolCall(
        AgentContext currentContext,
        AssistantMessage assistantMessage,
        ToolCallContent toolCall,
        AgentTool<?> tool,
        Consumer<AgentEvent> emit
    ) {
        return Mono.fromSupplier(() -> {
            try {
                Map<String, Object> args = (Map<String, Object>) toolCall.arguments();

                AgentToolResult<?> executedResult = ((AgentTool<Object>) tool).execute(
                    toolCall.id(),
                    args,
                    partial -> emit.accept(new AgentEvent.ToolExecutionUpdate(
                        toolCall.id(),
                        toolCall.name(),
                        args,
                        partial
                    ))
                );

                ToolResultMessage result = ToolResultMessage.success(
                    toolCall.id(),
                    toolCall.name(),
                    executedResult.content(),
                    executedResult.details()
                );

                // Apply afterToolCall hook if configured
                if (config.afterToolCall() != null) {
                    AfterToolCallContext hookContext = new AfterToolCallContext(
                        assistantMessage,
                        toolCall,
                        args,
                        executedResult,
                        false,
                        currentContext
                    );

                    try {
                        AfterToolCallResult hookResult = config.afterToolCall()
                            .apply(hookContext, null)
                            .block();
                        if (hookResult != null) {
                            result = applyAfterToolCallResult(result, hookResult);
                        }
                    } catch (Exception e) {
                        log.error("afterToolCall hook error: {}", e.getMessage(), e);
                    }
                }

                return finalizeToolResult(toolCall, result, false, emit);

            } catch (Exception e) {
                log.error("Tool execution error for {}: {}", toolCall.name(), e.getMessage(), e);
                ToolResultMessage result = ToolResultMessage.error(
                    toolCall.id(),
                    toolCall.name(),
                    e.getMessage() != null ? e.getMessage() : String.valueOf(e)
                );
                return finalizeToolResult(toolCall, result, true, emit);
            }
        });
    }

    private ToolResultMessage applyAfterToolCallResult(ToolResultMessage original, AfterToolCallResult hookResult) {
        List<Content> content = hookResult.content() != null
            ? hookResult.content()
            : original.content();
        Object details = hookResult.details() != null
            ? hookResult.details()
            : original.details();
        boolean isError = hookResult.isError() != null
            ? hookResult.isError()
            : original.isError();

        if (isError) {
            StringBuilder textBuilder = new StringBuilder();
            for (Content c : content) {
                if (c instanceof TextContent tc) {
                    textBuilder.append(tc.text());
                }
            }
            return ToolResultMessage.error(original.toolCallId(), original.toolName(), textBuilder.toString());
        } else {
            return ToolResultMessage.success(original.toolCallId(), original.toolName(), content, details);
        }
    }

    private ToolResultMessage finalizeToolResult(
        ToolCallContent toolCall,
        ToolResultMessage result,
        boolean isError,
        Consumer<AgentEvent> emit
    ) {
        AgentToolResult<Object> toolResult = new AgentToolResult<>(
            result.content(),
            result.details()
        );

        emit.accept(new AgentEvent.ToolExecutionEnd(
            toolCall.id(),
            toolCall.name(),
            toolResult,
            isError
        ));

        emit.accept(new AgentEvent.MessageStart(result));
        emit.accept(new AgentEvent.MessageEnd(result));

        return result;
    }
}
