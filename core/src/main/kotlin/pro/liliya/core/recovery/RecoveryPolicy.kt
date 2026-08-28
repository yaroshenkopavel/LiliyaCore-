package pro.liliya.core.recovery

class RecoveryPolicy(
    val maxAttempts: Int = 3
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
    }

    fun select(attempt: Int): RecoveryAction {
        require(attempt >= 1) { "attempt must be at least 1" }

        return when {
            attempt < maxAttempts -> RecoveryAction.RETRY
            attempt == maxAttempts -> RecoveryAction.RESTART
            else -> RecoveryAction.FAIL
        }
    }
}
