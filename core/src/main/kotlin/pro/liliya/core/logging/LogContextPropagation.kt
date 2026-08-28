package pro.liliya.core.logging

object LogContextPropagation {
    fun root(
        module: String,
        component: String,
        operation: String,
        metadata: Map<String, String> = emptyMap()
    ): LogContext =
        LogContext(
            module = module,
            component = component,
            operation = operation,
            correlationId = CorrelationIdGenerator.next(),
            parentCorrelationId = null,
            metadata = metadata
        )

    fun child(
        parent: LogContext,
        module: String = parent.module,
        component: String,
        operation: String,
        metadata: Map<String, String> = emptyMap()
    ): LogContext =
        LogContext(
            module = module,
            component = component,
            operation = operation,
            correlationId = CorrelationIdGenerator.next(),
            parentCorrelationId = parent.correlationId,
            metadata = parent.metadata + metadata
        )
}
