package pro.liliya.core.cognitive

import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.modelengine.ModelEngineCloseResult
import pro.liliya.core.modelengine.ModelEngineInferenceFailure
import pro.liliya.core.modelengine.ModelEngineInferenceRequest
import pro.liliya.core.modelengine.ModelEngineInferenceResult
import pro.liliya.core.modelengine.ModelEngineLoadResult
import pro.liliya.core.modelengine.ModelEngineLoaderPort
import pro.liliya.core.modelengine.ModelEngineSessionOwnership
import pro.liliya.core.protectedmodel.ProtectedModelAccessCoordinator
import pro.liliya.core.protectedmodel.ProtectedModelAccessResult
import pro.liliya.core.protectedmodel.ProtectedModelPackageEnvelope
import pro.liliya.core.protectedmodel.ProtectedModelPlaintextConsumer
import pro.liliya.core.runtime.hardening.RuntimeFailedSessionRetirementResult
import pro.liliya.core.runtime.hardening.RuntimeHardeningFailure
import pro.liliya.core.runtime.hardening.RuntimeHardeningLimits
import pro.liliya.core.runtime.hardening.RuntimeModelActivationCoordinator
import pro.liliya.core.runtime.hardening.RuntimeModelActivationResult
import pro.liliya.core.runtime.hardening.RuntimeModelOperationSupervisor
import pro.liliya.core.runtime.hardening.RuntimeModelSessionId
import pro.liliya.core.runtime.hardening.RuntimeModelSessionLifecycle
import pro.liliya.core.runtime.hardening.RuntimeModelSessionReference
import pro.liliya.core.runtime.hardening.RuntimeModelSessionRegistry
import pro.liliya.core.runtime.hardening.RuntimeOperationAdmissionResult
import pro.liliya.core.runtime.hardening.RuntimeOperationReleaseResult
import pro.liliya.core.runtime.hardening.RuntimeOperationTerminal
import pro.liliya.core.runtime.hardening.RuntimeRetirementRecoveryResult
import pro.liliya.core.runtime.hardening.RuntimeSessionDrainRetirementResult
import pro.liliya.core.runtime.hardening.RuntimeSessionFailureResult
import pro.liliya.core.runtime.hardening.RuntimeSessionQuiescenceResult

fun interface CognitiveModelRuntimeSessionIdSource {
    fun next(): RuntimeModelSessionId
}

enum class CognitiveModelActivationFailure {
    BUSY,
    LIVE_SESSION_EXISTS,
    SESSION_ID_FAILED,
    PROTECTED_ACCESS_REJECTED,
    PROTECTED_ACCESS_FAILED,
    ENGINE_LOAD_REJECTED,
    RUNTIME_ACTIVATION_REJECTED,
    RUNTIME_ACTIVATION_FAILED,
    CLEANUP_FAILED
}

sealed interface CognitiveModelActivationResult {
    data class Activated(
        val session: RuntimeModelSessionReference
    ) : CognitiveModelActivationResult

    data class Rejected(
        val reason: CognitiveModelActivationFailure
    ) : CognitiveModelActivationResult

    data class Failed(
        val reason: CognitiveModelActivationFailure
    ) : CognitiveModelActivationResult
}

sealed interface CognitiveModelQuiesceResult {
    data object Quiescing : CognitiveModelQuiesceResult
    data object AlreadyQuiescing : CognitiveModelQuiesceResult
    data object Stale : CognitiveModelQuiesceResult
}

sealed interface CognitiveModelRetirementResult {
    data object Retired : CognitiveModelRetirementResult
    data class DrainRequired(val inFlightOperations: Int) : CognitiveModelRetirementResult
    data object Stale : CognitiveModelRetirementResult
    data object CleanupFailed : CognitiveModelRetirementResult
}

sealed interface CognitiveModelCleanupResult {
    data object Cleaned : CognitiveModelCleanupResult
    data object NothingPending : CognitiveModelCleanupResult
    data object Busy : CognitiveModelCleanupResult
    data class DrainRequired(val inFlightOperations: Int) : CognitiveModelCleanupResult
    data object Stale : CognitiveModelCleanupResult
    data object Failed : CognitiveModelCleanupResult
}

/**
 * Process-local Slice 6 bridge from CognitiveInferencePort to one exact loaded model-engine session.
 *
 * Provider state locks are never held across Protected Model, Runtime Hardening, engine infer or engine
 * close calls. The engine binding is resource ownership only and is never exposed through inference.
 */
class CognitiveModelRuntimeComposition(
    private val foundation: FoundationComposition,
    private val protectedAccess: ProtectedModelAccessCoordinator,
    private val engineLoader: ModelEngineLoaderPort,
    private val compiler: CognitiveModelRequestCompilerPort,
    private val sessionIds: CognitiveModelRuntimeSessionIdSource,
    private val limits: CognitiveRuntimeLimits = CognitiveRuntimeLimits(),
    registry: RuntimeModelSessionRegistry? = null
) {
    private data class Binding(
        val session: RuntimeModelSessionReference,
        val engine: ModelEngineSessionOwnership
    )

    private val stateLock = Any()
    private val sessions = registry ?: RuntimeModelSessionRegistry()
    private val supervisor = RuntimeModelOperationSupervisor(
        registry = sessions,
        limits = RuntimeHardeningLimits(maxInFlightOperationsPerSession = 1)
    )
    private val activation = RuntimeModelActivationCoordinator(sessions)

    private var activationInProgress = false
    private var pendingCleanupInProgress = false
    private var binding: Binding? = null
    private var pendingActivationCleanup: ModelEngineSessionOwnership? = null

    val inferencePort: CognitiveInferencePort = CognitiveInferencePort { request -> infer(request) }

    fun currentSession(): RuntimeModelSessionReference? = sessions.currentReference()

    fun currentLifecycle(): RuntimeModelSessionLifecycle? = sessions.currentLifecycle()

    fun activateModel(
        envelope: ProtectedModelPackageEnvelope,
        ciphertext: ByteArray
    ): CognitiveModelActivationResult {
        if (!reserveActivation()) {
            return activationRejected(CognitiveModelActivationFailure.BUSY)
        }

        if (sessions.currentReference() != null) {
            finishActivationReservation()
            return activationRejected(CognitiveModelActivationFailure.LIVE_SESSION_EXISTS)
        }

        val sessionId = try {
            sessionIds.next()
        } catch (_: Exception) {
            finishActivationReservation()
            return activationFailed(CognitiveModelActivationFailure.SESSION_ID_FAILED)
        }

        var attemptOwnership: ModelEngineSessionOwnership? = null
        val opened = protectedAccess.openAndPublish(
            envelope = envelope,
            ciphertext = ciphertext,
            consumer = ProtectedModelPlaintextConsumer { model, plaintext ->
                when (val loaded = engineLoader.load(model, plaintext)) {
                    is ModelEngineLoadResult.Loaded -> {
                        attemptOwnership = loaded.ownership
                        loaded
                    }
                    is ModelEngineLoadResult.Rejected -> loaded
                }
            },
            publish = { _, _ -> Unit }
        )

        when (opened) {
            is ProtectedModelAccessResult.Rejected -> {
                val owned = attemptOwnership
                return if (owned == null) {
                    finishActivationReservation()
                    activationRejected(CognitiveModelActivationFailure.PROTECTED_ACCESS_REJECTED)
                } else {
                    compensateUntransferred(
                        owned,
                        CognitiveModelActivationFailure.PROTECTED_ACCESS_REJECTED
                    )
                }
            }

            is ProtectedModelAccessResult.Failed -> {
                val owned = attemptOwnership
                return if (owned == null) {
                    finishActivationReservation()
                    activationFailed(CognitiveModelActivationFailure.PROTECTED_ACCESS_FAILED)
                } else {
                    compensateUntransferred(
                        owned,
                        CognitiveModelActivationFailure.PROTECTED_ACCESS_FAILED
                    )
                }
            }

            is ProtectedModelAccessResult.Opened -> Unit
        }

        val loadResult = opened.value
        val engine = when (loadResult) {
            is ModelEngineLoadResult.Rejected -> {
                finishActivationReservation()
                return activationRejected(CognitiveModelActivationFailure.ENGINE_LOAD_REJECTED)
            }
            is ModelEngineLoadResult.Loaded -> loadResult.ownership
        }

        val activationResult = activation.activate(
            sessionId = sessionId,
            openedModel = ProtectedModelAccessResult.Opened(opened.reference, engine)
        ) { session, ownership ->
            synchronized(stateLock) {
                check(activationInProgress) { "model activation reservation must remain active" }
                check(binding == null) { "model engine binding already exists" }
                check(pendingActivationCleanup == null) { "pending engine cleanup blocks activation" }
                binding = Binding(session, ownership)
            }
        }

        return when (activationResult) {
            is RuntimeModelActivationResult.Activated -> {
                finishActivationReservation()
                record(
                    DiagnosticSeverity.INFO,
                    "COGNITIVE_MODEL_RUNTIME_ACTIVATED",
                    "cognitive model runtime activated",
                    activationResult.session,
                    mapOf("backendId" to engine.backendId.toString())
                )
                CognitiveModelActivationResult.Activated(activationResult.session)
            }

            is RuntimeModelActivationResult.Rejected -> {
                compensateUntransferred(
                    engine,
                    CognitiveModelActivationFailure.RUNTIME_ACTIVATION_REJECTED
                )
            }

            is RuntimeModelActivationResult.Failed -> {
                compensateUntransferred(
                    engine,
                    CognitiveModelActivationFailure.RUNTIME_ACTIVATION_FAILED
                )
            }
        }
    }

    fun recoverPendingActivationCleanup(): CognitiveModelCleanupResult {
        val engine = synchronized(stateLock) {
            if (activationInProgress || pendingCleanupInProgress) {
                return CognitiveModelCleanupResult.Busy
            }
            val pending = pendingActivationCleanup ?: return CognitiveModelCleanupResult.NothingPending
            pendingCleanupInProgress = true
            pending
        }

        val closed = closeEngine(engine)
        synchronized(stateLock) {
            pendingCleanupInProgress = false
            if (closed && pendingActivationCleanup === engine) {
                pendingActivationCleanup = null
            }
        }
        return if (closed) {
            record(
                DiagnosticSeverity.INFO,
                "COGNITIVE_MODEL_PENDING_CLEANUP_RECOVERED",
                "pending model engine cleanup recovered"
            )
            CognitiveModelCleanupResult.Cleaned
        } else {
            record(
                DiagnosticSeverity.ERROR,
                "COGNITIVE_MODEL_PENDING_CLEANUP_FAILED",
                "pending model engine cleanup failed"
            )
            CognitiveModelCleanupResult.Failed
        }
    }

    fun beginQuiescing(session: RuntimeModelSessionReference): CognitiveModelQuiesceResult =
        when (supervisor.beginQuiescing(session)) {
            RuntimeSessionQuiescenceResult.Quiescing -> CognitiveModelQuiesceResult.Quiescing
            RuntimeSessionQuiescenceResult.AlreadyQuiescing -> CognitiveModelQuiesceResult.AlreadyQuiescing
            RuntimeSessionQuiescenceResult.Stale -> CognitiveModelQuiesceResult.Stale
        }

    fun retireIfDrained(session: RuntimeModelSessionReference): CognitiveModelRetirementResult {
        val exactBinding = bindingFor(session) ?: return CognitiveModelRetirementResult.Stale
        return when (
            supervisor.retireIfDrained(session) {
                if (!closeEngine(exactBinding.engine)) {
                    throw EngineCleanupException()
                }
                synchronized(stateLock) {
                    if (binding === exactBinding) {
                        binding = null
                    }
                }
            }
        ) {
            RuntimeSessionDrainRetirementResult.Retired -> {
                record(
                    DiagnosticSeverity.INFO,
                    "COGNITIVE_MODEL_RUNTIME_RETIRED",
                    "cognitive model runtime retired",
                    session
                )
                CognitiveModelRetirementResult.Retired
            }
            is RuntimeSessionDrainRetirementResult.DrainRequired -> CognitiveModelRetirementResult.DrainRequired(
                supervisor.inFlightCount(session)
            )
            RuntimeSessionDrainRetirementResult.Stale -> CognitiveModelRetirementResult.Stale
            is RuntimeSessionDrainRetirementResult.Failed -> {
                record(
                    DiagnosticSeverity.ERROR,
                    "COGNITIVE_MODEL_RUNTIME_RETIREMENT_CLEANUP_FAILED",
                    "cognitive model runtime retirement cleanup failed",
                    session
                )
                CognitiveModelRetirementResult.CleanupFailed
            }
        }
    }

    fun recoverRetirementFailure(session: RuntimeModelSessionReference): CognitiveModelRetirementResult {
        val exactBinding = bindingFor(session) ?: return CognitiveModelRetirementResult.Stale
        return when (
            supervisor.recoverRetirementFailure(session) {
                if (!closeEngine(exactBinding.engine)) {
                    throw EngineCleanupException()
                }
                synchronized(stateLock) {
                    if (binding === exactBinding) {
                        binding = null
                    }
                }
            }
        ) {
            RuntimeRetirementRecoveryResult.Retired -> CognitiveModelRetirementResult.Retired
            RuntimeRetirementRecoveryResult.Stale -> CognitiveModelRetirementResult.Stale
            is RuntimeRetirementRecoveryResult.Failed -> CognitiveModelRetirementResult.CleanupFailed
        }
    }

    fun cleanupFailedSession(session: RuntimeModelSessionReference): CognitiveModelCleanupResult {
        val inFlight = supervisor.inFlightCount(session)
        if (inFlight > 0) {
            return CognitiveModelCleanupResult.DrainRequired(inFlight)
        }

        val exactBinding = bindingFor(session) ?: return CognitiveModelCleanupResult.Stale
        if (!closeEngine(exactBinding.engine)) {
            record(
                DiagnosticSeverity.ERROR,
                "COGNITIVE_MODEL_FAILED_SESSION_CLEANUP_FAILED",
                "failed model session cleanup failed",
                session
            )
            return CognitiveModelCleanupResult.Failed
        }

        synchronized(stateLock) {
            if (binding === exactBinding) {
                binding = null
            }
        }

        return when (supervisor.retireFailed(session)) {
            RuntimeFailedSessionRetirementResult.Retired -> CognitiveModelCleanupResult.Cleaned
            RuntimeFailedSessionRetirementResult.Stale -> CognitiveModelCleanupResult.Stale
        }
    }

    private fun infer(request: CognitiveInferenceRequest): CognitiveInferenceResult {
        if (request.maxOutputChars > limits.maxInferenceOutputChars) {
            return rejected(request, CognitiveInferenceFailure.RESOURCE_LIMIT_REJECTED)
        }

        val compiled = try {
            compiler.compile(
                CognitiveModelRequestCompilerRequest(
                    inference = request,
                    maxPromptChars = limits.maxModelPromptChars
                )
            )
        } catch (_: Exception) {
            return rejected(request, CognitiveInferenceFailure.PROVIDER_FAILED)
        }

        val compiledRequest = when (compiled) {
            is CognitiveModelRequestCompilerResult.Rejected -> {
                val failure = when (compiled.reason) {
                    CognitiveModelRequestCompilerFailure.RESOURCE_LIMIT_REJECTED ->
                        CognitiveInferenceFailure.RESOURCE_LIMIT_REJECTED
                    CognitiveModelRequestCompilerFailure.COMPILER_REJECTED ->
                        CognitiveInferenceFailure.PROVIDER_REJECTED
                    CognitiveModelRequestCompilerFailure.PROVIDER_FAILED ->
                        CognitiveInferenceFailure.PROVIDER_FAILED
                }
                return rejected(request, failure)
            }
            is CognitiveModelRequestCompilerResult.Compiled -> compiled.request
        }

        if (compiledRequest.prompt.isBlank()) {
            return rejected(request, CognitiveInferenceFailure.PROVIDER_FAILED)
        }
        if (compiledRequest.prompt.length > limits.maxModelPromptChars) {
            return rejected(request, CognitiveInferenceFailure.RESOURCE_LIMIT_REJECTED)
        }

        val ticket = when (val admitted = supervisor.admit()) {
            is RuntimeOperationAdmissionResult.Admitted -> admitted.ticket
            is RuntimeOperationAdmissionResult.Rejected -> {
                val failure = if (admitted.reason == RuntimeHardeningFailure.RESOURCE_LIMIT_REJECTED) {
                    CognitiveInferenceFailure.RESOURCE_LIMIT_REJECTED
                } else {
                    CognitiveInferenceFailure.PROVIDER_REJECTED
                }
                return rejected(request, failure)
            }
        }

        val exactBinding = bindingFor(ticket.session)
        if (exactBinding == null) {
            containProviderFailure(ticket.session, ticket, RuntimeOperationTerminal.FAILED)
            return rejected(request, CognitiveInferenceFailure.PROVIDER_FAILED)
        }

        val engineResult = try {
            exactBinding.engine.infer(
                ModelEngineInferenceRequest(
                    prompt = compiledRequest.prompt,
                    maxOutputChars = request.maxOutputChars
                )
            )
        } catch (_: Exception) {
            containProviderFailure(ticket.session, ticket, RuntimeOperationTerminal.FAILED)
            return rejected(request, CognitiveInferenceFailure.PROVIDER_FAILED)
        }

        return when (engineResult) {
            is ModelEngineInferenceResult.Rejected -> handleEngineRejection(
                request,
                ticket.session,
                ticket,
                engineResult.reason
            )
            is ModelEngineInferenceResult.Succeeded -> handleEngineSuccess(
                request,
                ticket.session,
                ticket,
                engineResult.output
            )
        }
    }

    private fun handleEngineRejection(
        request: CognitiveInferenceRequest,
        session: RuntimeModelSessionReference,
        ticket: pro.liliya.core.runtime.hardening.RuntimeModelOperationTicket,
        failure: ModelEngineInferenceFailure
    ): CognitiveInferenceResult {
        val terminal = when (failure) {
            ModelEngineInferenceFailure.CANCELLED -> RuntimeOperationTerminal.CANCELLED
            ModelEngineInferenceFailure.TIMED_OUT -> RuntimeOperationTerminal.TIMED_OUT
            else -> RuntimeOperationTerminal.FAILED
        }
        val release = supervisor.release(ticket, terminal)
        if (release is RuntimeOperationReleaseResult.AlreadyReleased) {
            supervisor.failSession(session, RuntimeHardeningFailure.PROVIDER_FAILED)
            return rejected(request, CognitiveInferenceFailure.PROVIDER_FAILED)
        }

        return when (failure) {
            ModelEngineInferenceFailure.REQUEST_REJECTED ->
                rejected(request, CognitiveInferenceFailure.PROVIDER_REJECTED)
            ModelEngineInferenceFailure.RESOURCE_LIMIT_REJECTED ->
                rejected(request, CognitiveInferenceFailure.RESOURCE_LIMIT_REJECTED)
            ModelEngineInferenceFailure.OPERATION_FAILED,
            ModelEngineInferenceFailure.CANCELLED,
            ModelEngineInferenceFailure.TIMED_OUT ->
                rejected(request, CognitiveInferenceFailure.PROVIDER_FAILED)
            ModelEngineInferenceFailure.SESSION_FAILED -> {
                supervisor.failSession(session, RuntimeHardeningFailure.SESSION_FAILED)
                rejected(request, CognitiveInferenceFailure.PROVIDER_FAILED)
            }
            ModelEngineInferenceFailure.PROVIDER_FAILED -> {
                supervisor.failSession(session, RuntimeHardeningFailure.PROVIDER_FAILED)
                rejected(request, CognitiveInferenceFailure.PROVIDER_FAILED)
            }
        }
    }

    private fun handleEngineSuccess(
        request: CognitiveInferenceRequest,
        session: RuntimeModelSessionReference,
        ticket: pro.liliya.core.runtime.hardening.RuntimeModelOperationTicket,
        output: String
    ): CognitiveInferenceResult {
        if (
            output.isBlank() ||
            output.length > request.maxOutputChars ||
            output.length > limits.maxInferenceOutputChars
        ) {
            containProviderFailure(session, ticket, RuntimeOperationTerminal.FAILED)
            return rejected(request, CognitiveInferenceFailure.PROVIDER_FAILED)
        }

        var publicationAccepted = false
        return when (
            supervisor.release(ticket, RuntimeOperationTerminal.SUCCEEDED) {
                publicationAccepted = true
            }
        ) {
            RuntimeOperationReleaseResult.Published -> {
                if (!publicationAccepted) {
                    supervisor.failSession(session, RuntimeHardeningFailure.PROVIDER_FAILED)
                    rejected(request, CognitiveInferenceFailure.PROVIDER_FAILED)
                } else {
                    CognitiveInferenceResult.Succeeded(request.turn, output)
                }
            }
            RuntimeOperationReleaseResult.Stale ->
                rejected(request, CognitiveInferenceFailure.PROVIDER_REJECTED)
            RuntimeOperationReleaseResult.AlreadyReleased -> {
                supervisor.failSession(session, RuntimeHardeningFailure.PROVIDER_FAILED)
                rejected(request, CognitiveInferenceFailure.PROVIDER_FAILED)
            }
            is RuntimeOperationReleaseResult.Terminated -> {
                supervisor.failSession(session, RuntimeHardeningFailure.PROVIDER_FAILED)
                rejected(request, CognitiveInferenceFailure.PROVIDER_FAILED)
            }
            is RuntimeOperationReleaseResult.Failed -> {
                supervisor.failSession(session, RuntimeHardeningFailure.PROVIDER_FAILED)
                rejected(request, CognitiveInferenceFailure.PROVIDER_FAILED)
            }
        }
    }

    private fun containProviderFailure(
        session: RuntimeModelSessionReference,
        ticket: pro.liliya.core.runtime.hardening.RuntimeModelOperationTicket,
        terminal: RuntimeOperationTerminal
    ) {
        supervisor.release(ticket, terminal)
        supervisor.failSession(session, RuntimeHardeningFailure.PROVIDER_FAILED)
    }

    private fun reserveActivation(): Boolean = synchronized(stateLock) {
        if (
            activationInProgress ||
            pendingCleanupInProgress ||
            binding != null ||
            pendingActivationCleanup != null
        ) {
            false
        } else {
            activationInProgress = true
            true
        }
    }

    private fun finishActivationReservation() {
        synchronized(stateLock) {
            activationInProgress = false
        }
    }

    private fun compensateUntransferred(
        engine: ModelEngineSessionOwnership,
        originalFailure: CognitiveModelActivationFailure
    ): CognitiveModelActivationResult {
        val closed = closeEngine(engine)
        synchronized(stateLock) {
            activationInProgress = false
            if (!closed) {
                pendingActivationCleanup = engine
            }
        }
        return if (closed) {
            activationRejected(originalFailure)
        } else {
            activationFailed(CognitiveModelActivationFailure.CLEANUP_FAILED)
        }
    }

    private fun bindingFor(session: RuntimeModelSessionReference): Binding? = synchronized(stateLock) {
        binding?.takeIf { it.session == session }
    }

    private fun closeEngine(engine: ModelEngineSessionOwnership): Boolean = try {
        engine.close() is ModelEngineCloseResult.Closed
    } catch (_: Exception) {
        false
    }

    private fun rejected(
        request: CognitiveInferenceRequest,
        reason: CognitiveInferenceFailure
    ): CognitiveInferenceResult.Rejected {
        record(
            if (reason == CognitiveInferenceFailure.PROVIDER_FAILED) {
                DiagnosticSeverity.ERROR
            } else {
                DiagnosticSeverity.WARNING
            },
            "COGNITIVE_MODEL_INFERENCE_REJECTED",
            "cognitive model inference rejected",
            metadata = mapOf(
                "reason" to reason.name,
                "promptOutputBudgetChars" to request.maxOutputChars.toString()
            )
        )
        return CognitiveInferenceResult.Rejected(request.turn, reason)
    }

    private fun activationRejected(reason: CognitiveModelActivationFailure): CognitiveModelActivationResult.Rejected {
        record(
            DiagnosticSeverity.WARNING,
            "COGNITIVE_MODEL_ACTIVATION_REJECTED",
            "cognitive model activation rejected",
            metadata = mapOf("reason" to reason.name)
        )
        return CognitiveModelActivationResult.Rejected(reason)
    }

    private fun activationFailed(reason: CognitiveModelActivationFailure): CognitiveModelActivationResult.Failed {
        record(
            DiagnosticSeverity.ERROR,
            "COGNITIVE_MODEL_ACTIVATION_FAILED",
            "cognitive model activation failed",
            metadata = mapOf("reason" to reason.name)
        )
        return CognitiveModelActivationResult.Failed(reason)
    }

    private fun record(
        severity: DiagnosticSeverity,
        code: String,
        message: String,
        session: RuntimeModelSessionReference? = null,
        metadata: Map<String, String> = emptyMap()
    ) {
        val base = mutableMapOf<String, String>()
        if (session != null) {
            base["runtimeSessionGeneration"] = session.generation.value.toString()
            base["protectedModelGeneration"] = session.model.generation.value.toString()
        }
        base.putAll(metadata)
        val context = foundation.rootContext(
            operation = code.lowercase(),
            component = "CognitiveModelRuntime"
        )
        foundation.observability.record(
            severity,
            code,
            message,
            context,
            base
        )
    }

    private class EngineCleanupException : RuntimeException("engine cleanup failed")
}
