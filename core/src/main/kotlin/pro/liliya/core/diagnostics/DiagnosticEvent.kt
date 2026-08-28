package pro.liliya.core.diagnostics

import pro.liliya.core.logging.LogContext

data class DiagnosticEvent(
    val timestampMillis: Long,
    val sequence: Long,
    val severity: DiagnosticSeverity,
    val code: String,
    val message: String,
    val context: LogContext,
    val metadata: Map<String, String> = emptyMap(),
    val throwableType: String? = null,
    val throwableMessage: String? = null
)
