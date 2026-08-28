package pro.liliya.core.recovery

import pro.liliya.core.logging.LogContext

data class RecoveryRequest(
    val target: String,
    val reason: String,
    val attempt: Int,
    val context: LogContext
) {
    init {
        require(target.isNotBlank()) { "target must not be blank" }
        require(reason.isNotBlank()) { "reason must not be blank" }
        require(attempt >= 1) { "attempt must be at least 1" }
    }
}
