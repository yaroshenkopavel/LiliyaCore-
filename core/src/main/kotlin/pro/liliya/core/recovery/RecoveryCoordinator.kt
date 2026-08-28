package pro.liliya.core.recovery

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.DiagnosticSeverity
import java.util.concurrent.ConcurrentHashMap

class RecoveryCoordinator(
    private val policy: RecoveryPolicy,
    private val diagnostics: DiagnosticRecorder
) {
    private val activeTargets = ConcurrentHashMap.newKeySet<String>()

    fun decide(request: RecoveryRequest): RecoveryDecision {
        if (!activeTargets.add(request.target)) {
            diagnostics.record(
                severity = DiagnosticSeverity.WARNING,
                code = "RECOVERY_DUPLICATE_REJECTED",
                message = "Recovery request rejected because target is already being processed",
                context = request.context,
                metadata = mapOf(
                    "target" to request.target,
                    "attempt" to request.attempt.toString(),
                    "reason" to request.reason
                )
            )
            return RecoveryDecision.Rejected(
                request = request,
                reason = "recovery already active for target"
            )
        }

        return try {
            val action = policy.select(request.attempt)
            diagnostics.record(
                severity = DiagnosticSeverity.INFO,
                code = "RECOVERY_DECISION_SELECTED",
                message = "Recovery action selected",
                context = request.context,
                metadata = mapOf(
                    "target" to request.target,
                    "attempt" to request.attempt.toString(),
                    "action" to action.name,
                    "reason" to request.reason
                )
            )
            RecoveryDecision.Selected(
                request = request,
                action = action
            )
        } finally {
            activeTargets.remove(request.target)
        }
    }
}
