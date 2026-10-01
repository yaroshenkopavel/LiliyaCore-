package pro.liliya.core.autonomy

import java.time.Duration
import java.time.Instant

fun interface AutonomyExecutionRunner {
    fun execute(request: ControlledAutonomyExecutionRequest): ControlledAutonomyExecutionResult
}

sealed interface DurableControlledAutonomyExecutionResult {
    data object Succeeded : DurableControlledAutonomyExecutionResult
    data class Rejected(val reason: String) : DurableControlledAutonomyExecutionResult
    data class RecoveryRequired(val reason: String, val throwable: Throwable? = null) : DurableControlledAutonomyExecutionResult
}

/**
 * One-shot durable boundary around [ControlledAutonomyExecution].
 *
 * The durable claim is committed before the runner is invoked. Therefore a process death cannot
 * silently replay an action: an EXECUTING checkpoint is deliberately excluded from pending work and
 * appears in [PersistentAutonomyExecutionCheckpointStore.recoveryRequired] instead. No Authority is
 * persisted or restored; each live runner call still performs the frozen fresh-Authority checks.
 */
class DurableControlledAutonomyExecution(
    private val checkpoints: PersistentAutonomyExecutionCheckpointStore,
    private val runner: AutonomyExecutionRunner,
    private val maxPendingAge: Duration
) {
    init {
        require(!maxPendingAge.isNegative && !maxPendingAge.isZero) {
            "autonomy execution max pending age must be positive"
        }
    }

    constructor(
        checkpoints: PersistentAutonomyExecutionCheckpointStore,
        execution: ControlledAutonomyExecution,
        maxPendingAge: Duration
    ) : this(checkpoints, AutonomyExecutionRunner(execution::execute), maxPendingAge)

    fun execute(
        snapshot: AutonomyExecutionCheckpointSnapshot,
        now: Instant
    ): DurableControlledAutonomyExecutionResult {
        val intentId = snapshot.checkpoint.request.orchestrationIntentId
        val current = checkpoints.inspect(intentId)
            ?: return reject("autonomy execution checkpoint is not live")
        if (current.generation != snapshot.generation) {
            return reject("autonomy execution checkpoint generation is stale")
        }
        if (current.checkpoint.state != AutonomyExecutionCheckpointState.PENDING) {
            return reject("autonomy execution checkpoint is not pending")
        }
        if (now.isBefore(current.checkpoint.createdAt)) {
            return reject("autonomy execution clock moved before checkpoint creation")
        }
        if (Duration.between(current.checkpoint.createdAt, now) > maxPendingAge) {
            return when (val cancelled = checkpoints.cancel(intentId, current.generation, now)) {
                is AutonomyExecutionCheckpointWriteResult.Written -> reject("autonomy execution checkpoint expired")
                is AutonomyExecutionCheckpointWriteResult.Rejected -> reject(cancelled.reason)
                is AutonomyExecutionCheckpointWriteResult.Failed -> recovery(
                    "failed to durably cancel expired autonomy execution checkpoint",
                    cancelled.throwable
                )
            }
        }

        val claimed = when (val claim = checkpoints.markExecuting(intentId, current.generation, now)) {
            is AutonomyExecutionCheckpointWriteResult.Written -> claim.snapshot
            is AutonomyExecutionCheckpointWriteResult.Rejected -> return reject(claim.reason)
            is AutonomyExecutionCheckpointWriteResult.Failed -> return recovery(
                "failed to durably claim autonomy execution checkpoint",
                claim.throwable
            )
        }

        val executionResult = try {
            runner.execute(claimed.checkpoint.request)
        } catch (failure: Throwable) {
            checkpoints.markRecoveryRequired(intentId, claimed.generation, now)
            return recovery("autonomy execution runner threw after durable claim", failure)
        }

        return when (executionResult) {
            ControlledAutonomyExecutionResult.Succeeded -> when (
                val completed = checkpoints.markCompleted(intentId, claimed.generation, now)
            ) {
                is AutonomyExecutionCheckpointWriteResult.Written -> DurableControlledAutonomyExecutionResult.Succeeded
                is AutonomyExecutionCheckpointWriteResult.Rejected -> recovery(
                    "autonomy execution succeeded but completion checkpoint was rejected: ${completed.reason}"
                )
                is AutonomyExecutionCheckpointWriteResult.Failed -> recovery(
                    "autonomy execution succeeded but completion checkpoint failed",
                    completed.throwable
                )
            }

            is ControlledAutonomyExecutionResult.Rejected -> when (
                val rejected = checkpoints.markRejected(intentId, claimed.generation, now)
            ) {
                is AutonomyExecutionCheckpointWriteResult.Written -> reject(executionResult.reason)
                is AutonomyExecutionCheckpointWriteResult.Rejected -> recovery(
                    "autonomy execution was rejected but terminal checkpoint was rejected: ${rejected.reason}"
                )
                is AutonomyExecutionCheckpointWriteResult.Failed -> recovery(
                    "autonomy execution was rejected but terminal checkpoint failed",
                    rejected.throwable
                )
            }

            is ControlledAutonomyExecutionResult.Failed -> {
                checkpoints.markRecoveryRequired(intentId, claimed.generation, now)
                recovery(executionResult.reason, executionResult.throwable)
            }
        }
    }

    private fun reject(reason: String) = DurableControlledAutonomyExecutionResult.Rejected(reason)

    private fun recovery(
        reason: String,
        throwable: Throwable? = null
    ) = DurableControlledAutonomyExecutionResult.RecoveryRequired(reason, throwable)
}
