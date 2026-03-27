package com.pi.agent.config;

import com.pi.agent.dto.AgentDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for configuration and DTO classes.
 */
class AgentConfigTest {
    
    @Test
    @DisplayName("AgentProperties should have correct defaults")
    void propertiesDefaults() {
        AgentProperties props = new AgentProperties(
            new AgentProperties.ModelConfig("gpt-4", "openai", null, null),
            "Test prompt",
            null,  // thinkingLevel - should default to OFF
            null,  // toolExecution - should default to PARALLEL
            null,  // maxRetryDelay
            null,  // transport - should default to sse
            null,  // steering
            null,  // followUp
            null,  // tools
            null   // llm - should default to LlmConfig with defaults
        );
        
        assertEquals("gpt-4", props.model().id());
        assertEquals("openai", props.model().provider());
        assertEquals("Test prompt", props.systemPrompt());
        assertEquals(com.pi.agent.model.ThinkingLevel.OFF, props.thinkingLevel());
        assertEquals(com.pi.agent.model.ToolExecutionMode.PARALLEL, props.toolExecution());
        assertEquals("sse", props.transport());
        assertEquals("one_at_a_time", props.steering().mode());
        assertEquals("one_at_a_time", props.followUp().mode());
        assertTrue(props.tools().isEmpty());
    }
    
    @Test
    @DisplayName("PromptRequest should convert to content")
    void promptRequestToContent() {
        AgentDto.PromptRequest request = new AgentDto.PromptRequest(
            "Hello world",
            java.util.List.of("http://example.com/image.jpg"),
            "session-123",
            true
        );
        
        assertEquals("Hello world", request.text());
        assertEquals(1, request.images().size());
        assertEquals("session-123", request.sessionId());
        assertTrue(request.stream());
        
        var content = request.toContent();
        assertEquals(2, content.size());
        assertInstanceOf(com.pi.agent.model.content.TextContent.class, content.get(0));
        assertInstanceOf(com.pi.agent.model.content.ImageContent.class, content.get(1));
    }
    
    @Test
    @DisplayName("StatusResponse should contain correct data")
    void statusResponse() {
        AgentDto.StatusResponse response = new AgentDto.StatusResponse(
            "session-456",
            true,
            5,
            false
        );
        
        assertEquals("session-456", response.sessionId());
        assertTrue(response.streaming());
        assertEquals(5, response.messageCount());
        assertFalse(response.hasQueuedMessages());
    }
    
    @Test
    @DisplayName("ErrorResponse should format correctly")
    void errorResponse() {
        AgentDto.ErrorResponse error = new AgentDto.ErrorResponse(
            "Something went wrong",
            "ERR_001"
        );
        
        assertEquals("Something went wrong", error.error());
        assertEquals("ERR_001", error.code());
    }
    
    @Test
    @DisplayName("EventData should hold event data")
    void eventData() {
        AgentDto.EventData event = new AgentDto.EventData(
            "message_start",
            java.util.Map.of("text", "Hello")
        );
        
        assertEquals("message_start", event.type());
        assertNotNull(event.data());
    }
    
    @Test
    @DisplayName("ModelConfig record should work")
    void modelConfig() {
        AgentProperties.ModelConfig config = new AgentProperties.ModelConfig(
            "claude-3-opus",
            "anthropic",
            "sk-test",
            "https://api.anthropic.com"
        );
        
        assertEquals("claude-3-opus", config.id());
        assertEquals("anthropic", config.provider());
        assertEquals("sk-test", config.apiKey());
        assertEquals("https://api.anthropic.com", config.baseUrl());
    }
    
    @Test
    @DisplayName("SteeringConfig should have defaults")
    void steeringConfig() {
        AgentProperties.SteeringConfig config = new AgentProperties.SteeringConfig(null, null);
        
        assertEquals("one_at_a_time", config.mode());
        assertTrue(config.messages().isEmpty());
    }
    
    @Test
    @DisplayName("FollowUpConfig should have defaults")
    void followUpConfig() {
        AgentProperties.FollowUpConfig config = new AgentProperties.FollowUpConfig(null, null);
        
        assertEquals("one_at_a_time", config.mode());
        assertTrue(config.messages().isEmpty());
    }
    
    @Test
    @DisplayName("ToolConfig record should work")
    void toolConfig() {
        AgentProperties.ToolConfig tool = new AgentProperties.ToolConfig(
            "search",
            "Search the web",
            true
        );
        
        assertEquals("search", tool.name());
        assertEquals("Search the web", tool.description());
        assertTrue(tool.enabled());
    }
}
