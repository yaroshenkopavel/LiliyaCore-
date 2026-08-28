package pro.liliya.core.lifecycle

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LoggerFactory
import pro.liliya.core.observability.CoreObservability
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.runtime.RuntimeState
import pro.liliya.core.runtime.RuntimeStateController
import pro.liliya.core.runtime.RuntimeTransitionResult

class LifecycleController(
    private val runtime: RuntimeStateController,
    diagnostics: DiagnosticRecorder,
    private val observability: CoreObservability = CoreObservability(
        loggerProvider = LoggerProvider { context -> LoggerFactory.create(context) },
        diagnostics = diagnostics
    )
) {
    fun execute(command: LifecycleCommand): LifecycleResult {
        val target = targetState(command.phase)
        val result = runtime.transition(
            to = target,
            reason = command.reason,
            context = command.context
        )

        return when (result) {
            is RuntimeTransitionResult.Applied -> {
                observability.record(
                    severity = DiagnosticSeverity.INFO,
                    code = "LIFECYCLE_COMMAND_APPLIED",
                    message = "Lifecycle command applied: ${command.phase}",
                    context = command.context,
                    metadata = mapOf(
                        "phase" to command.phase.name,
                        "state" to result.transition.to.name,
                        "reason" to command.reason
                    )
                )
                LifecycleResult.Applied(
                    phase = command.phase,
                    resultingState = result.transition.to
                )
            }

            is RuntimeTransitionResult.Rejected -> {
                observability.record(
                    severity = DiagnosticSeverity.WARNING,
                    code = "LIFECYCLE_COMMAND_REJECTED",
                    message = "Lifecycle command rejected: ${command.phase}",
                    context = command.context,
                    metadata = mapOf(
                        "phase" to command.phase.name,
                        "state" to result.from.name,
                        "target" to result.to.name,
                        "reason" to command.reason
                    )
                )
                LifecycleResult.Rejected(
                    phase = command.phase,
                    currentState = result.from,
                    reason = command.reason
                )
            }
        }
    }

    private fun targetState(phase: LifecyclePhase): RuntimeState = when (phase) {
        LifecyclePhase.PREPARE -> RuntimeState.STARTING
        LifecyclePhase.START -> RuntimeState.RUNNING
        LifecyclePhase.STOP -> RuntimeState.STOPPING
    }
}
