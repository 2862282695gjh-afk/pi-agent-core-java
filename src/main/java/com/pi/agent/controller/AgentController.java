package com.pi.agent.controller;

import com.pi.agent.dto.AgentResponse;
import com.pi.agent.dto.PromptRequest;
import com.pi.agent.event.AgentEvent;
import com.pi.agent.service.Agent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentController {
    
    private final Agent agent;
    
    @PostMapping("/prompt")
    public Mono<ResponseEntity<AgentResponse>> prompt(@RequestBody PromptRequest request) {
        log.info("Received prompt: {}", request.getText());
        return agent.prompt(request.getText())
            .then(Mono.just(ResponseEntity.ok(AgentResponse.success("Prompt processed"))))
            .onErrorResume(e -> {
                log.error("Error processing prompt", e);
                return Mono.just(ResponseEntity.internalServerError()
                    .body(AgentResponse.error(e.getMessage())));
            });
    }
    
    @PostMapping(value = "/prompt/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentEvent> promptStream(@RequestBody PromptRequest request) {
        log.info("Received streaming prompt: {}", request.getText());
        return agent.prompt(request.getText());
    }
    
    @GetMapping("/state")
    public ResponseEntity<AgentResponse> getState() {
        return ResponseEntity.ok(AgentResponse.builder()
            .success(true)
            .state(agent.getState())
            .timestamp(System.currentTimeMillis())
            .build());
    }
    
    @PostMapping("/reset")
    public ResponseEntity<AgentResponse> reset() {
        agent.reset();
        return ResponseEntity.ok(AgentResponse.success("Agent reset"));
    }
    
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}
