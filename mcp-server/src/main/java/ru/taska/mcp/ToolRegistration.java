package ru.taska.mcp;

import java.util.function.Function;
import reactor.core.publisher.Mono;

public record ToolRegistration(
    ToolDefinition definition, Function<ToolInvocation, Mono<ToolData>> handler) {}
