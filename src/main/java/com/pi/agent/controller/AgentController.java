package com.pi.agent.controller;

import com.pi.agent.Agent;
import com.pi.agent.dto.AgentDto;
import com.pi.agent.dto.AgentDto.*;
import com.pi.agent.event.AgentEvent;
import com.pi.agent.model.message.AgentMessage;
import com.pi.agent.model.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST Controller for Agent API.
 * 
 * Endpoints:
 * - POST /api/agent/prompt - Send a prompt (streaming or non-streaming)
 * - POST /api/agent/messages - Send multiple messages
 * - GET /api/agent/status - Get agent status
 * - POST /api/agent/reset - Reset agent state
 * - POST /api/agent/steer - Add steering message
 * - POST /api/agent/followup - Add follow-up message
 */
@RestController
@RequestMapping("/api/agent")
@Validated
public class AgentController {
    
    private static final Logger log = LoggerFactory.getLogger(AgentController.class);
    
    private final Agent agent;
    private final Map<String, Agent> sessionAgents = new ConcurrentHashMap<>();
    
    public AgentController(Agent agent) {
        this.agent = agent;
    }
    
    /**
     * Send a prompt to the agent.
     * 
     * @param request The prompt request
     * @return SSE stream if stream=true, otherwise the response
     */
    @PostMapping("/prompt")
    public Mono<?> prompt(@Validated @RequestBody PromptRequest request) {
        log.debug("Received prompt: {}", request.text());
        
        Agent targetAgent = getOrCreateAgent(request.sessionId());
        
        if (Boolean.TRUE.equals(request.stream())) {
            // Return SSE stream
            return Mono.just(ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(streamPrompt(targetAgent, request)));
        } else {
            // Return complete response
            return collectPromptResponse(targetAgent, request)
                .map(ResponseEntity::ok);
        }
    }
    
    /**
     * Send multiple messages to the agent.
     */
    @PostMapping("/messages")
    public Mono<?> messages(@Validated @RequestBody MultiMessageRequest request) {
        log.debug("Received {} messages", request.messages().size());
        
        Agent targetAgent = getOrCreateAgent(request.sessionId());
        
        if (Boolean.TRUE.equals(request.stream())) {
            return Mono.just(ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(streamMessages(targetAgent, request)));
        } else {
            return Mono.error(new UnsupportedOperationException(
                "Non-streaming multi-message not yet implemented"));
        }
    }
    
    /**
     * Get agent status.
     */
    @GetMapping("/status")
    public Mono<StatusResponse> status(
        @RequestParam(required = false) String sessionId
    ) {
        Agent targetAgent = getOrCreateAgent(sessionId);
        
        return Mono.just(new StatusResponse(
            targetAgent.getSessionId(),
            targetAgent.isStreaming(),
            targetAgent.getState().messages().size(),
            targetAgent.hasQueuedMessages()
        ));
    }
    
    /**
     * Reset agent state.
     */
    @PostMapping("/reset")
    public Mono<Void> reset(
        @RequestParam(required = false) String sessionId
    ) {
        Agent targetAgent = getOrCreateAgent(sessionId);
        targetAgent.reset();
        log.info("Agent reset for session: {}", sessionId);
        return Mono.empty();
    }
    
    /**
     * Add a steering message.
     */
    @PostMapping("/steer")
    public Mono<Void> steer(
        @RequestBody PromptRequest request,
        @RequestParam(required = false) String sessionId
    ) {
        Agent targetAgent = getOrCreateAgent(sessionId);
        UserMessage message = new UserMessage(request.toContent());
        targetAgent.steer(message);
        log.debug("Steering message added: {}", request.text());
        return Mono.empty();
    }
    
    /**
     * Add a follow-up message.
     */
    @PostMapping("/followup")
    public Mono<Void> followUp(
        @RequestBody PromptRequest request,
        @RequestParam(required = false) String sessionId
    ) {
        Agent targetAgent = getOrCreateAgent(sessionId);
        UserMessage message = new UserMessage(request.toContent());
        targetAgent.followUp(message);
        log.debug("Follow-up message added: {}", request.text());
        return Mono.empty();
    }
    
    /**
     * Clear all queued messages.
     */
    @DeleteMapping("/queue")
    public Mono<Void> clearQueue(
        @RequestParam(required = false) String sessionId
    ) {
        Agent targetAgent = getOrCreateAgent(sessionId);
        targetAgent.clearAllQueues();
        log.debug("Queue cleared for session: {}", sessionId);
        return Mono.empty();
    }
    
    // === Helper Methods ===
    
    private Agent getOrCreateAgent(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return agent;
        }
        
        return sessionAgents.computeIfAbsent(sessionId, id -> {
            Agent.Builder builder = Agent.builder();
            
            // Copy configuration from main agent
            builder.systemPrompt(agent.getState().systemPrompt());
            builder.model(agent.getState().model());
            builder.thinkingLevel(agent.getState().thinkingLevel());
            builder.sessionId(id);
            
            return builder.build();
        });
    }
    
    private Flux<ServerSentEvent<EventData>> streamPrompt(Agent agent, PromptRequest request) {
        return agent.prompt(request.text(), List.of())
            .map(event -> toSse(event))
            .concatWith(Flux.just(
                ServerSentEvent.<EventData>builder()
                    .event("done")
                    .data(new EventData("done", Map.of("sessionId", agent.getSessionId())))
                    .build()
            ))
            .onErrorResume(e -> {
                log.error("Error in stream: {}", e.getMessage());
                return Flux.just(
                    ServerSentEvent.<EventData>builder()
                        .event("error")
                        .data(new EventData("error", new ErrorResponse(e.getMessage(), "STREAM_ERROR")))
                        .build()
                );
            });
    }
    
    private Flux<ServerSentEvent<EventData>> streamMessages(Agent agent, MultiMessageRequest request) {
        List<AgentMessage> messages = request.messages().stream()
            .<AgentMessage>map(m -> new UserMessage(List.of(new com.pi.agent.model.content.TextContent(m.content().toString()))))
            .toList();
        
        return agent.prompt(messages)
            .map(event -> toSse(event))
            .concatWith(Flux.just(
                ServerSentEvent.<EventData>builder()
                    .event("done")
                    .data(new EventData("done", Map.of("sessionId", agent.getSessionId())))
                    .build()
            ))
            .onErrorResume(e -> {
                log.error("Error in stream: {}", e.getMessage());
                return Flux.just(
                    ServerSentEvent.<EventData>builder()
                        .event("error")
                        .data(new EventData("error", new ErrorResponse(e.getMessage(), "STREAM_ERROR")))
                        .build()
                );
            });
    }
    
    private Mono<PromptResponse> collectPromptResponse(Agent agent, PromptRequest request) {
        return agent.prompt(request.text(), List.of())
            .collectList()
            .map(events -> {
                List<MessageDto> messages = agent.getState().messages().stream()
                    .map(m -> new MessageDto(m.role(), m.toString()))
                    .toList();
                
                return new PromptResponse(agent.getSessionId(), messages);
            });
    }
    
    private ServerSentEvent<EventData> toSse(AgentEvent event) {
        String eventType = getEventType(event);
        EventData data = new EventData(eventType, event);
        
        return ServerSentEvent.<EventData>builder()
            .event(eventType)
            .data(data)
            .build();
    }
    
    private String getEventType(AgentEvent event) {
        return switch (event) {
            case AgentEvent.AgentStart ignored -> "agent_start";
            case AgentEvent.AgentEnd ignored -> "agent_end";
            case AgentEvent.TurnStart ignored -> "turn_start";
            case AgentEvent.TurnEnd ignored -> "turn_end";
            case AgentEvent.MessageStart msg -> "message_start";
            case AgentEvent.MessageUpdate msg -> "message_update";
            case AgentEvent.MessageEnd msg -> "message_end";
            case AgentEvent.ToolExecutionStart tc -> "tool_execution_start";
            case AgentEvent.ToolExecutionUpdate tc -> "tool_execution_update";
            case AgentEvent.ToolExecutionEnd tc -> "tool_execution_end";
        };
    }
}
