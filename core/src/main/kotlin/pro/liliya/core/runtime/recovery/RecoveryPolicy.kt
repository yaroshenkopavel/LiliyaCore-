package pro.liliya.core.runtime.recovery

class RecoveryPolicy(
    val maxAttempts: Int = 3
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
    }

    fun actionFor(request: RecoveryRequest): RecoveryAction = when {
        request.attempt < maxAttempts -> RecoveryAction.RETRY
        request.attempt == maxAttempts -> RecoveryAction.RESTART
        else -> RecoveryAction.FAIL
    }
}
