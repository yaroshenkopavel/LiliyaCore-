package pro.liliya.core.recovery

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LoggerFactory
import pro.liliya.core.observability.CoreObservability
import pro.liliya.core.observability.LoggerProvider
import java.util.concurrent.ConcurrentHashMap

class RecoveryCoordinator(
    private val policy: RecoveryPolicy,
    diagnostics: DiagnosticRecorder,
    private val observability: CoreObservability = CoreObservability(
        loggerProvider = LoggerProvider { context -> LoggerFactory.create(context) },
        diagnostics = diagnostics
    )
) {
    private val activeTargets = ConcurrentHashMap.newKeySet<String>()

    fun decide(request: RecoveryRequest): RecoveryDecision {
        if (!activeTargets.add(request.target)) {
            observability.record(
                severity = DiagnosticSeverity.WARNING,
                code = "RECOVERY_DUPLICATE_REJECTED",
                message = "Recovery request rejected because target is already active",
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

        val action = policy.select(request.attempt)
        observability.record(
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
        return RecoveryDecision.Selected(
            request = request,
            action = action
        )
    }

    fun complete(request: RecoveryRequest): Boolean {
        val released = activeTargets.remove(request.target)
        observability.record(
            severity = if (released) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
            code = if (released) "RECOVERY_COMPLETED" else "RECOVERY_COMPLETION_IGNORED",
            message = if (released) {
                "Recovery target released after completion"
            } else {
                "Recovery completion ignored because target was not active"
            },
            context = request.context,
            metadata = mapOf(
                "target" to request.target,
                "attempt" to request.attempt.toString(),
                "reason" to request.reason
            )
        )
        return released
    }

    fun isActive(target: String): Boolean = activeTargets.contains(target)
}
