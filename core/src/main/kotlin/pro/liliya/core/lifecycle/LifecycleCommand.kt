package pro.liliya.core.lifecycle

import pro.liliya.core.logging.LogContext

data class LifecycleCommand(
    val phase: LifecyclePhase,
    val reason: String,
    val context: LogContext
)
