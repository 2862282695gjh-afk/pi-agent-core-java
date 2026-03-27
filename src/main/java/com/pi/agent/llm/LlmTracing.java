package com.pi.agent.llm;

import com.pi.agent.event.AssistantMessageEvent;
import com.pi.agent.model.AgentState;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Distributed tracing support for LLM operations.
 * Provides OpenTelemetry-compatible span management without direct dependency.
 * 
 * In production, this integrates with Spring Boot's tracing via Micrometer Tracing.
 * The spans can be exported to Jaeger, Zipkin, or other tracing backends.
 */
public class LlmTracing {

    private final String serviceName;
    private final boolean enabled;
    private final AtomicLong totalSpans = new AtomicLong(0);
    private final ConcurrentHashMap<String, Span> activeSpans = new ConcurrentHashMap<>();

    // For integration with external tracing systems
    private final SpanProcessor spanProcessor;

    /**
     * Create a tracing instance with default settings.
     */
    public LlmTracing() {
        this("pi-agent-llm", true, null);
    }

    /**
     * Create a tracing instance.
     * 
     * @param serviceName The service name for spans
     * @param enabled Whether tracing is enabled
     * @param spanProcessor Optional processor for span events (e.g., export to backend)
     */
    public LlmTracing(String serviceName, boolean enabled, SpanProcessor spanProcessor) {
        this.serviceName = serviceName;
        this.enabled = enabled;
        this.spanProcessor = spanProcessor != null ? spanProcessor : SpanProcessor.NOOP;
    }

    /**
     * Start a new span for an LLM request.
     */
    public Span startSpan(String operationName, AgentState.ModelInfo model) {
        if (!enabled) {
            return Span.NOOP;
        }

        Span span = new Span(
            generateSpanId(),
            operationName,
            serviceName,
            model.provider(),
            model.id(),
            Instant.now()
        );

        activeSpans.put(span.spanId(), span);
        totalSpans.incrementAndGet();
        spanProcessor.onStart(span);

        return span;
    }

    /**
     * Start a child span.
     */
    public Span startChildSpan(Span parent, String operationName) {
        if (!enabled || parent == Span.NOOP) {
            return Span.NOOP;
        }

        Span span = new Span(
            generateSpanId(),
            parent.spanId(),
            operationName,
            serviceName,
            parent.provider(),
            parent.model(),
            Instant.now()
        );

        activeSpans.put(span.spanId(), span);
        totalSpans.incrementAndGet();
        spanProcessor.onStart(span);

        return span;
    }

    /**
     * Wrap a stream with tracing.
     */
    public <T> Flux<T> traceStream(Flux<T> stream, Span span, String eventType) {
        if (!enabled || span == Span.NOOP) {
            return stream;
        }

        return stream
            .doOnSubscribe(s -> span.addEvent(eventType + ".subscribed"))
            .doOnNext(event -> {
                if (event instanceof AssistantMessageEvent ame) {
                    span.addEvent(ame.getClass().getSimpleName());
                }
            })
            .doOnComplete(() -> {
                span.end(Span.Status.OK);
                activeSpans.remove(span.spanId());
            })
            .doOnError(e -> {
                span.end(Span.Status.ERROR, e.getMessage());
                activeSpans.remove(span.spanId());
            });
    }

    /**
     * Get active span count.
     */
    public int getActiveSpanCount() {
        return activeSpans.size();
    }

    /**
     * Get total spans created.
     */
    public long getTotalSpans() {
        return totalSpans.get();
    }

    /**
     * Generate a unique span ID.
     */
    private String generateSpanId() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * Represents a tracing span.
     */
    public static class Span {
        public static final Span NOOP = new Span("", "", "", "", "", "", Instant.now()) {
            @Override
            public void addEvent(String name) {}
            @Override
            public void setAttribute(String key, String value) {}
            @Override
            public void setStatus(Status status) {}
            @Override
            public void end(Status status) {}
            @Override
            public void end(Status status, String error) {}
        };

        private final String spanId;
        private final String parentSpanId;
        private final String operationName;
        private final String serviceName;
        private final String provider;
        private final String model;
        private final Instant startTime;
        private final ConcurrentHashMap<String, String> attributes = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Instant> events = new ConcurrentHashMap<>();
        private volatile Status status = Status.UNSET;
        private volatile String errorMessage;
        private volatile Instant endTime;

        public enum Status {
            UNSET, OK, ERROR
        }

        public Span(String spanId, String operationName, String serviceName, 
                   String provider, String model, Instant startTime) {
            this(spanId, null, operationName, serviceName, provider, model, startTime);
        }

        public Span(String spanId, String parentSpanId, String operationName, 
                   String serviceName, String provider, String model, Instant startTime) {
            this.spanId = spanId;
            this.parentSpanId = parentSpanId;
            this.operationName = operationName;
            this.serviceName = serviceName;
            this.provider = provider;
            this.model = model;
            this.startTime = startTime;
        }

        /**
         * Add an event to the span.
         */
        public void addEvent(String name) {
            events.put(name, Instant.now());
        }

        /**
         * Set a span attribute.
         */
        public void setAttribute(String key, String value) {
            attributes.put(key, value);
        }

        /**
         * Set span status.
         */
        public void setStatus(Status status) {
            this.status = status;
        }

        /**
         * End the span with a status.
         */
        public void end(Status status) {
            this.status = status;
            this.endTime = Instant.now();
        }

        /**
         * End the span with an error.
         */
        public void end(Status status, String error) {
            this.status = status;
            this.errorMessage = error;
            this.endTime = Instant.now();
        }

        /**
         * Get span duration.
         */
        public Duration getDuration() {
            if (endTime == null) {
                return Duration.between(startTime, Instant.now());
            }
            return Duration.between(startTime, endTime);
        }

        // Getters
        public String spanId() { return spanId; }
        public String parentSpanId() { return parentSpanId; }
        public String operationName() { return operationName; }
        public String serviceName() { return serviceName; }
        public String provider() { return provider; }
        public String model() { return model; }
        public Instant startTime() { return startTime; }
        public Instant endTime() { return endTime; }
        public Status status() { return status; }
        public String errorMessage() { return errorMessage; }
        public Map<String, String> attributes() { return Map.copyOf(attributes); }
        public Map<String, Instant> events() { return Map.copyOf(events); }
    }

    /**
     * Interface for processing span events.
     * Implement this to integrate with tracing backends.
     */
    public interface SpanProcessor {
        SpanProcessor NOOP = new SpanProcessor() {
            @Override
            public void onStart(Span span) {}
            @Override
            public void onEnd(Span span) {}
        };

        /**
         * Called when a span starts.
         */
        void onStart(Span span);

        /**
         * Called when a span ends.
         */
        void onEnd(Span span);
    }

    /**
     * Builder for LlmTracing.
     */
    public static class Builder {
        private String serviceName = "pi-agent-llm";
        private boolean enabled = true;
        private SpanProcessor spanProcessor;

        public Builder serviceName(String serviceName) {
            this.serviceName = serviceName;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder spanProcessor(SpanProcessor spanProcessor) {
            this.spanProcessor = spanProcessor;
            return this;
        }

        public LlmTracing build() {
            return new LlmTracing(serviceName, enabled, spanProcessor);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
