package pro.liliya.core.diagnostics

data class DiagnosticFailure(
    val sequence: Long,
    val code: String,
    val severity: DiagnosticSeverity,
    val sinkType: String,
    val throwableType: String,
    val throwableMessage: String?
)
