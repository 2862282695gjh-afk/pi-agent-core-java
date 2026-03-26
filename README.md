# PI Agent Core - Java

A Java/Spring Boot port of the [pi-agent-core](https://github.com/mariozechner/pi-agent-core) TypeScript library.

## Features

- **Reactive**: Built on Project Reactor (Flux/Mono) for non-blocking operations
- **Streaming**: Full support for streaming LLM responses
- **Tool System**: Flexible tool execution with hooks (before/after)
- **Event System**: Fine-grained lifecycle events for UI updates

## Tech Stack

- Java 21
- Spring Boot 3.3
- Spring WebFlux
- Project Reactor
- Lombok

## Project Structure

```
src/main/java/com/pi/agent/
├── model/          # Data models (AgentMessage, AgentState, etc.)
├── event/          # Event system (AgentEvent types)
├── service/        # Core services (Agent, AgentLoop)
├── config/         # Spring configuration
└── PiAgentApplication.java
```

## Original TypeScript Source

This is a port of the TypeScript agent library located at:
`~/Developer/pi-mono/packages/agent/`

## Progress

- [ ] Phase 1: Project skeleton & types
- [ ] Phase 2: Event system
- [ ] Phase 3: Agent core (Agent, AgentLoop)
- [ ] Phase 4: Tool system
- [ ] Phase 5: Configuration & Spring integration
- [ ] Phase 6: Tests
- [ ] Phase 7: Documentation

## License

MIT
