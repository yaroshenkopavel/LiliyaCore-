package pro.liliya.core.recovery

import pro.liliya.core.logging.LogContext

data class RecoveryRequest(
    val target: String,
    val reason: String,
    val attempt: Int,
    val context: LogContext
)
