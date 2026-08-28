package pro.liliya.core.runtime

import pro.liliya.core.logging.LogContext

data class RuntimeTransition(
    val from: RuntimeState,
    val to: RuntimeState,
    val reason: String,
    val context: LogContext
)
