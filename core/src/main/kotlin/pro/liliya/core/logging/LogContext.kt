package pro.liliya.core.logging

data class LogContext(
    val module: String,
    val component: String,
    val operation: String,
    val correlationId: String? = null,
    val parentCorrelationId: String? = null,
    val metadata: Map<String, String> = emptyMap()
)
