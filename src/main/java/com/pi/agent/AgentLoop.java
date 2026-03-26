package com.pi.agent;

import com.pi.agent.event.AgentEvent;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Agent loop implementation using Project Reactor.
 */
public class AgentLoop {

    private final AgentContext context;
    private final AgentLoopConfig config;
    private final Consumer<AgentEvent> eventSink;

    public AgentLoop(AgentContext context, AgentLoopConfig config, Consumer<AgentEvent> eventSink) {
        this.context = context;
        this.config = config;
        this.eventSink = eventSink;
    }

    /**
     * Run the agent loop with new prompt messages.
     */
    @SuppressWarnings("unchecked")
    public Flux<AgentMessage> run(List<AgentMessage> prompts) {
        List<AgentMessage> newMessages = new ArrayList<>(prompts);
        AgentContext currentContext = context.withMessages(new ArrayList<>() {{
            addAll(context.messages());
            addAll(prompts);
        }});

        return Flux.create(sink -> {
            Consumer<AgentEvent> emit = event -> sink.next(event);
            
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
                    e -> sink.error(e)
                );
        }).doOnNext(e -> eventSink.accept((AgentEvent) e)).thenMany(Flux.fromIterable(newMessages));
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

            // Stream assistant response (placeholder)
            List<Content> placeholderContent = List.of(new TextContent("LLM integration pending"));
            AssistantMessage message = new AssistantMessage(
                placeholderContent,
                "placeholder",
                "model"
            );

            emit.accept(new AgentEvent.MessageStart(message));
            newMessages.add(message);
            emit.accept(new AgentEvent.MessageEnd(message));

            if (message.stopReason() == StopReason.ERROR || message.stopReason() == StopReason.ABORTED) {
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
    }

    private Mono<List<ToolResultMessage>> executeToolCalls(
        AgentContext currentContext,
        AssistantMessage message,
        List<ToolCallContent> toolCalls,
        Consumer<AgentEvent> emit
    ) {
        List<ToolResultMessage> results = new ArrayList<>();

        return Flux.fromIterable(toolCalls)
            .flatMap(toolCall -> executeToolCall(currentContext, message, toolCall, emit)
                .doOnNext(results::add))
            .then(Mono.just(results));
    }

    private Mono<ToolResultMessage> executeToolCall(
        AgentContext currentContext,
        AssistantMessage assistantMessage,
        ToolCallContent toolCall,
        Consumer<AgentEvent> emit
    ) {
        return Mono.fromSupplier(() -> {
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
                return finalizeToolResult(toolCall, result, true, emit);
            }

            try {
                @SuppressWarnings("unchecked")
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

                return finalizeToolResult(toolCall, result, false, emit);

            } catch (Exception e) {
                ToolResultMessage result = ToolResultMessage.error(
                    toolCall.id(),
                    toolCall.name(),
                    e.getMessage() != null ? e.getMessage() : String.valueOf(e)
                );
                return finalizeToolResult(toolCall, result, true, emit);
            }
        });
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
