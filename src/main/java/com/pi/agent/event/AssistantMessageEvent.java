package com.pi.agent.event;

import com.pi.agent.model.message.AssistantMessage;
import java.util.Map;

/**
 * Events emitted during assistant message streaming.
 * Mirrors the TypeScript AssistantMessageEvent union type.
 */
public sealed interface AssistantMessageEvent permits
    AssistantMessageEvent.Start,
    AssistantMessageEvent.TextStart,
    AssistantMessageEvent.TextDelta,
    AssistantMessageEvent.TextEnd,
    AssistantMessageEvent.ThinkingStart,
    AssistantMessageEvent.ThinkingDelta,
    AssistantMessageEvent.ThinkingEnd,
    AssistantMessageEvent.ToolCallStart,
    AssistantMessageEvent.ToolCallDelta,
    AssistantMessageEvent.ToolCallEnd,
    AssistantMessageEvent.Done,
    AssistantMessageEvent.Error {

    String type();

    /**
     * Start of assistant message stream.
     */
    record Start(AssistantMessage partial) implements AssistantMessageEvent {
        @Override
        public String type() {
            return "start";
        }
    }

    /**
     * Start of text content block.
     */
    record TextStart(int index) implements AssistantMessageEvent {
        @Override
        public String type() {
            return "text_start";
        }
    }

    /**
     * Text content delta.
     */
    record TextDelta(int index, String delta) implements AssistantMessageEvent {
        @Override
        public String type() {
            return "text_delta";
        }
    }

    /**
     * End of text content block.
     */
    record TextEnd(int index) implements AssistantMessageEvent {
        @Override
        public String type() {
            return "text_end";
        }
    }

    /**
     * Start of thinking content block.
     */
    record ThinkingStart(int index) implements AssistantMessageEvent {
        @Override
        public String type() {
            return "thinking_start";
        }
    }

    /**
     * Thinking content delta.
     */
    record ThinkingDelta(int index, String delta) implements AssistantMessageEvent {
        @Override
        public String type() {
            return "thinking_delta";
        }
    }

    /**
     * End of thinking content block.
     */
    record ThinkingEnd(int index) implements AssistantMessageEvent {
        @Override
        public String type() {
            return "thinking_end";
        }
    }

    /**
     * Start of tool call content block.
     */
    record ToolCallStart(int index, String id, String name) implements AssistantMessageEvent {
        @Override
        public String type() {
            return "toolcall_start";
        }
    }

    /**
     * Tool call argument delta.
     */
    record ToolCallDelta(int index, String id, String name, String argumentsDelta) implements AssistantMessageEvent {
        @Override
        public String type() {
            return "toolcall_delta";
        }
    }

    /**
     * End of tool call content block.
     */
    record ToolCallEnd(int index, String id, String name, Map<String, Object> arguments) implements AssistantMessageEvent {
        @Override
        public String type() {
            return "toolcall_end";
        }
    }

    /**
     * Stream completed successfully.
     */
    record Done(AssistantMessage message) implements AssistantMessageEvent {
        @Override
        public String type() {
            return "done";
        }
    }

    /**
     * Stream error.
     */
    record Error(String message, Throwable cause) implements AssistantMessageEvent {
        public Error(String message) {
            this(message, null);
        }

        @Override
        public String type() {
            return "error";
        }
    }
}
