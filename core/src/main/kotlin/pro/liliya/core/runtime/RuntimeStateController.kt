package pro.liliya.core.runtime

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext

class RuntimeStateController(
    private val stateHolder: RuntimeStateHolder,
    private val transitionPolicy: RuntimeTransitionPolicy,
    private val diagnostics: DiagnosticRecorder
) {
    fun currentState(): RuntimeState = stateHolder.current()

    fun transition(
        to: RuntimeState,
        reason: String,
        context: LogContext
    ): RuntimeTransitionResult {
        while (true) {
            val from = stateHolder.current()
            val transition = RuntimeTransition(
                from = from,
                to = to,
                reason = reason,
                context = context
            )

            if (!transitionPolicy.allows(from, to)) {
                diagnostics.record(
                    severity = DiagnosticSeverity.WARNING,
                    code = "RUNTIME_TRANSITION_REJECTED",
                    message = "Runtime transition rejected: $from -> $to",
                    context = context,
                    metadata = mapOf(
                        "from" to from.name,
                        "to" to to.name,
                        "reason" to reason
                    )
                )
                return RuntimeTransitionResult.Rejected(
                    from = from,
                    to = to,
                    reason = reason
                )
            }

            if (stateHolder.compareAndSet(from, to)) {
                diagnostics.record(
                    severity = DiagnosticSeverity.INFO,
                    code = "RUNTIME_TRANSITION_APPLIED",
                    message = "Runtime transition applied: $from -> $to",
                    context = context,
                    metadata = mapOf(
                        "from" to from.name,
                        "to" to to.name,
                        "reason" to reason
                    )
                )
                return RuntimeTransitionResult.Applied(transition)
            }
        }
    }
}
