package com.pi.agent.model;

import com.pi.agent.model.content.TextContent;
import com.pi.agent.model.content.ImageContent;
import com.pi.agent.model.hook.BeforeToolCallResult;
import com.pi.agent.model.hook.AfterToolCallResult;
import com.pi.agent.model.message.UserMessage;
import com.pi.agent.model.message.AssistantMessage;
import com.pi.agent.model.message.ToolResultMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the agent type system.
 */
class AgentModelTest {

    @Test
    void testThinkingLevelValues() {
        assertEquals("off", ThinkingLevel.OFF.getValue());
        assertEquals("minimal", ThinkingLevel.MINIMAL.getValue());
        assertEquals("low", ThinkingLevel.LOW.getValue());
        assertEquals("medium", ThinkingLevel.MEDIUM.getValue());
        assertEquals("high", ThinkingLevel.HIGH.getValue());
        assertEquals("xhigh", ThinkingLevel.XHIGH.getValue());
    }

    @Test
    void testThinkingLevelFromValue() {
        assertEquals(ThinkingLevel.OFF, ThinkingLevel.fromValue("off"));
        assertEquals(ThinkingLevel.HIGH, ThinkingLevel.fromValue("high"));
        assertEquals(ThinkingLevel.OFF, ThinkingLevel.fromValue("unknown")); // defaults to OFF
    }

    @Test
    void testToolExecutionMode() {
        assertEquals("sequential", ToolExecutionMode.SEQUENTIAL.getValue());
        assertEquals("parallel", ToolExecutionMode.PARALLEL.getValue());
        assertEquals(ToolExecutionMode.SEQUENTIAL, ToolExecutionMode.fromValue("sequential"));
        assertEquals(ToolExecutionMode.PARALLEL, ToolExecutionMode.fromValue("parallel"));
    }

    @Test
    void testStopReason() {
        assertEquals("end_turn", StopReason.END_TURN.getValue());
        assertEquals("error", StopReason.ERROR.getValue());
        assertEquals("aborted", StopReason.ABORTED.getValue());
    }

    @Test
    void testTextContent() {
        TextContent content = new TextContent("Hello, world!");
        assertEquals("text", content.type());
        assertEquals("Hello, world!", content.text());
    }

    @Test
    void testImageContent() {
        ImageContent content = new ImageContent("https://example.com/image.png", "image/png");
        assertEquals("image", content.type());
        assertEquals("https://example.com/image.png", content.url());
        assertEquals("image/png", content.mimeType());
    }

    @Test
    void testUserMessage() {
        UserMessage message = UserMessage.of("Hello");
        assertEquals("user", message.role());
        assertEquals(1, message.content().size());
        assertInstanceOf(TextContent.class, message.content().get(0));
        assertEquals("Hello", ((TextContent) message.content().get(0)).text());
    }

    @Test
    void testAssistantMessage() {
        AssistantMessage message = AssistantMessage.of("Response", "google", "gemini-2.0-flash");
        assertEquals("assistant", message.role());
        assertEquals("google", message.provider());
        assertEquals("gemini-2.0-flash", message.model());
        assertFalse(message.hasError());
        assertFalse(message.hasToolCalls());
    }

    @Test
    void testToolResultMessage() {
        List<com.pi.agent.model.content.Content> content = List.of(new TextContent("Result"));
        ToolResultMessage message = ToolResultMessage.success("call-1", "test-tool", content, null);
        
        assertEquals("toolResult", message.role());
        assertEquals("call-1", message.toolCallId());
        assertEquals("test-tool", message.toolName());
        assertFalse(message.isError());
    }

    @Test
    void testToolResultMessageError() {
        ToolResultMessage message = ToolResultMessage.error("call-1", "test-tool", "Tool failed");
        
        assertEquals("toolResult", message.role());
        assertTrue(message.isError());
        assertEquals("Tool failed", ((TextContent) message.content().get(0)).text());
    }

    @Test
    void testAgentState() {
        AgentState state = new AgentState();
        
        assertEquals("", state.systemPrompt());
        assertFalse(state.isStreaming());
        assertTrue(state.messages().isEmpty());
        assertTrue(state.tools().isEmpty());
    }

    @Test
    void testAgentStateWithSystemPrompt() {
        AgentState state = new AgentState().withSystemPrompt("You are a helpful assistant.");
        
        assertEquals("You are a helpful assistant.", state.systemPrompt());
    }

    @Test
    void testAgentContextBuilder() {
        AgentContext context = AgentContext.builder()
            .systemPrompt("Test prompt")
            .messages(List.of(UserMessage.of("Hello")))
            .build();
        
        assertEquals("Test prompt", context.systemPrompt());
        assertEquals(1, context.messages().size());
    }

    @Test
    void testAgentToolResult() {
        List<com.pi.agent.model.content.Content> content = List.of(new TextContent("Success"));
        AgentToolResult<String> result = AgentToolResult.of(content, "details");
        
        assertEquals(1, result.content().size());
        assertEquals("details", result.details());
    }

    @Test
    void testAgentToolResultError() {
        AgentToolResult<Void> result = AgentToolResult.error("Something went wrong");
        
        assertEquals(1, result.content().size());
        assertNull(result.details());
    }

    @Test
    void testBeforeToolCallResult() {
        assertFalse(BeforeToolCallResult.allow().blocked());
        assertTrue(BeforeToolCallResult.doBlock("Not allowed").blocked());
        assertEquals("Not allowed", BeforeToolCallResult.doBlock("Not allowed").reason());
    }

    @Test
    void testAfterToolCallResult() {
        AfterToolCallResult empty = AfterToolCallResult.empty();
        assertNull(empty.content());
        assertNull(empty.details());
        assertNull(empty.isError());
        
        List<com.pi.agent.model.content.Content> content = List.of(new TextContent("Override"));
        AfterToolCallResult custom = AfterToolCallResult.builder()
            .content(content)
            .isError(true)
            .build();
        
        assertEquals(1, custom.content().size());
        assertTrue(custom.isError());
    }

    private void assertInstanceOf(Class<?> expected, Object actual) {
        assertTrue(expected.isInstance(actual), 
            "Expected " + expected.getName() + " but got " + actual.getClass().getName());
    }
}
