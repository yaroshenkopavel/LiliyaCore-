package pro.liliya.core.runtime

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

class RuntimeStateController(
    private val stateHolder: RuntimeStateHolder,
    private val transitionPolicy: RuntimeTransitionPolicy,
    private val diagnostics: DiagnosticRecorder,
    private val observability: CoreObservability? = null
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
                record(
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
                record(
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

    private fun record(
        severity: DiagnosticSeverity,
        code: String,
        message: String,
        context: LogContext,
        metadata: Map<String, String>
    ) {
        val bridge = observability
        if (bridge != null) {
            bridge.record(severity, code, message, context, metadata)
        } else {
            diagnostics.record(severity, code, message, context, metadata)
        }
    }
}
