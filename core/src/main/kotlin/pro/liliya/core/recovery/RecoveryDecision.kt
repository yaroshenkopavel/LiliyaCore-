package pro.liliya.core.recovery

sealed interface RecoveryDecision {
    data class Selected(
        val request: RecoveryRequest,
        val action: RecoveryAction
    ) : RecoveryDecision

    data class Rejected(
        val request: RecoveryRequest,
        val reason: String
    ) : RecoveryDecision
}
