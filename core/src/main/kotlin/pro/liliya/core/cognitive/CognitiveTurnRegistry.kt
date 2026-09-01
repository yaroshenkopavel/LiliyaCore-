package pro.liliya.core.cognitive

import java.util.concurrent.atomic.AtomicLong

sealed interface CognitiveTurnRegistrationResult {
    data class Registered(val turn: CognitiveTurnHandle) : CognitiveTurnRegistrationResult
    data class Rejected(val reason: CognitiveTurnRegistrationFailure) : CognitiveTurnRegistrationResult
}

enum class CognitiveTurnRegistrationFailure {
    LIVE_TURN_EXISTS,
    TURN_ID_LIMIT_REJECTED,
    INPUT_LIMIT_REJECTED,
    GENERATION_OVERFLOW
}

sealed interface CognitiveTurnPublicationResult {
    data object Published : CognitiveTurnPublicationResult
    data object Stale : CognitiveTurnPublicationResult
    data class Rejected(val reason: CognitiveTurnFailure) : CognitiveTurnPublicationResult
    data class Failed(val throwable: Throwable) : CognitiveTurnPublicationResult {
        override fun toString(): String = "Failed(throwable=${throwable.javaClass.name})"
    }
}

sealed interface CognitiveTurnTransitionResult {
    data object Transitioned : CognitiveTurnTransitionResult
    data object Stale : CognitiveTurnTransitionResult
    data class Failed(val reason: CognitiveTurnFailure) : CognitiveTurnTransitionResult
}

interface CognitiveTurnHandle {
    val reference: CognitiveTurnReference

    fun isCurrent(): Boolean
    fun lifecycle(): CognitiveTurnLifecycle
}

internal data class AcceptedCognitionReceipt(
    val turn: CognitiveTurnReference,
    val planning: PlanningReference,
    val reasoning: ReasoningReference,
    val decision: DecisionReference
)

class CognitiveTurnRegistry internal constructor(
    private val limits: CognitiveRuntimeLimits,
    initialGeneration: Long = 0L
) {
    private data class Entry(
        val reference: CognitiveTurnReference,
        val input: CognitiveInput,
        var lifecycle: CognitiveTurnLifecycle = CognitiveTurnLifecycle.CREATED,
        var context: CognitiveContextSnapshot? = null,
        var inference: CognitiveInferenceResult.Succeeded? = null,
        var acceptedCognition: AcceptedCognitionReceipt? = null,
        var publicationInProgress: Boolean = false
    )

    private val lock = Any()
    private val nextGeneration = AtomicLong(initialGeneration)
    private var current: Entry? = null

    fun register(
        id: CognitiveTurnId,
        input: CognitiveInput
    ): CognitiveTurnRegistrationResult = synchronized(lock) {
        check(current?.publicationInProgress != true) {
            "cognitive turn registration is not allowed from inside publication"
        }
        if (current != null) {
            return@synchronized CognitiveTurnRegistrationResult.Rejected(
                CognitiveTurnRegistrationFailure.LIVE_TURN_EXISTS
            )
        }
        if (id.value.length > limits.maxTurnIdChars) {
            return@synchronized CognitiveTurnRegistrationResult.Rejected(
                CognitiveTurnRegistrationFailure.TURN_ID_LIMIT_REJECTED
            )
        }
        if (input.text.length > limits.maxInputChars) {
            return@synchronized CognitiveTurnRegistrationResult.Rejected(
                CognitiveTurnRegistrationFailure.INPUT_LIMIT_REJECTED
            )
        }

        val nextValue = nextGeneration.incrementAndGet()
        if (nextValue <= 0L) {
            return@synchronized CognitiveTurnRegistrationResult.Rejected(
                CognitiveTurnRegistrationFailure.GENERATION_OVERFLOW
            )
        }

        val entry = Entry(
            reference = CognitiveTurnReference(id, CognitiveTurnGeneration(nextValue)),
            input = input
        )
        current = entry
        CognitiveTurnRegistrationResult.Registered(handle(entry))
    }

    fun currentReference(): CognitiveTurnReference? = synchronized(lock) { current?.reference }
    fun currentLifecycle(): CognitiveTurnLifecycle? = synchronized(lock) { current?.lifecycle }

    internal fun isCurrentAt(
        reference: CognitiveTurnReference,
        lifecycle: CognitiveTurnLifecycle
    ): Boolean = synchronized(lock) {
        val entry = current
        entry != null && entry.reference == reference && entry.lifecycle == lifecycle
    }

    internal fun inputIfCurrent(reference: CognitiveTurnReference): CognitiveInput? = synchronized(lock) {
        current?.takeIf { it.reference == reference }?.input
    }

    internal fun contextIfCurrent(reference: CognitiveTurnReference): CognitiveContextSnapshot? = synchronized(lock) {
        current?.takeIf { it.reference == reference }?.context
    }

    internal fun inferenceIfCurrent(reference: CognitiveTurnReference): CognitiveInferenceResult.Succeeded? = synchronized(lock) {
        current?.takeIf { it.reference == reference }?.inference
    }

    internal fun acceptedCognitionIfCurrent(reference: CognitiveTurnReference): AcceptedCognitionReceipt? = synchronized(lock) {
        current?.takeIf { it.reference == reference }?.acceptedCognition
    }

    internal fun publishContextIfCurrent(
        reference: CognitiveTurnReference,
        context: CognitiveContextSnapshot,
        publish: () -> Unit = {}
    ): CognitiveTurnPublicationResult = synchronized(lock) {
        val entry = current
        if (entry == null || entry.reference != reference || entry.lifecycle != CognitiveTurnLifecycle.CREATED) {
            return@synchronized CognitiveTurnPublicationResult.Stale
        }
        if (context.turn != entry.reference || !contextWithinLimits(context)) {
            return@synchronized CognitiveTurnPublicationResult.Rejected(
                CognitiveTurnFailure.CONTEXT_REJECTED
            )
        }
        check(!entry.publicationInProgress) { "nested cognitive turn publication is not allowed" }
        entry.publicationInProgress = true
        try {
            publish()
            if (current !== entry || entry.lifecycle != CognitiveTurnLifecycle.CREATED) {
                CognitiveTurnPublicationResult.Stale
            } else {
                entry.context = context
                entry.lifecycle = CognitiveTurnLifecycle.CONTEXT_READY
                CognitiveTurnPublicationResult.Published
            }
        } catch (throwable: Throwable) {
            CognitiveTurnPublicationResult.Failed(throwable)
        } finally {
            entry.publicationInProgress = false
        }
    }

    internal fun beginGeneratingIfCurrent(
        reference: CognitiveTurnReference
    ): CognitiveTurnTransitionResult = synchronized(lock) {
        val entry = current
        if (entry == null || entry.reference != reference || entry.lifecycle != CognitiveTurnLifecycle.CONTEXT_READY) {
            return@synchronized CognitiveTurnTransitionResult.Stale
        }
        check(!entry.publicationInProgress) {
            "cognitive generation transition is not allowed from inside publication"
        }
        entry.lifecycle = CognitiveTurnLifecycle.GENERATING
        CognitiveTurnTransitionResult.Transitioned
    }

    internal fun publishAcceptedCognitionIfCurrent(
        reference: CognitiveTurnReference,
        inference: CognitiveInferenceResult.Succeeded,
        receipt: AcceptedCognitionReceipt,
        publish: () -> Unit = {}
    ): CognitiveTurnPublicationResult = synchronized(lock) {
        val entry = current
        if (entry == null || entry.reference != reference || entry.lifecycle != CognitiveTurnLifecycle.GENERATING) {
            return@synchronized CognitiveTurnPublicationResult.Stale
        }
        if (inference.turn != entry.reference || receipt.turn != entry.reference) {
            return@synchronized CognitiveTurnPublicationResult.Rejected(
                CognitiveTurnFailure.INFERENCE_REJECTED
            )
        }
        if (!inferenceWithinLimits(inference)) {
            return@synchronized CognitiveTurnPublicationResult.Rejected(
                CognitiveTurnFailure.INFERENCE_REJECTED
            )
        }
        check(!entry.publicationInProgress) { "nested cognitive turn publication is not allowed" }
        entry.publicationInProgress = true
        try {
            publish()
            if (current !== entry || entry.lifecycle != CognitiveTurnLifecycle.GENERATING) {
                CognitiveTurnPublicationResult.Stale
            } else {
                entry.inference = inference
                entry.acceptedCognition = receipt
                entry.lifecycle = CognitiveTurnLifecycle.COGNITION_READY
                CognitiveTurnPublicationResult.Published
            }
        } catch (throwable: Throwable) {
            CognitiveTurnPublicationResult.Failed(throwable)
        } finally {
            entry.publicationInProgress = false
        }
    }

    internal fun completeIfCurrent(
        reference: CognitiveTurnReference
    ): CognitiveTurnTransitionResult = synchronized(lock) {
        val entry = current
        if (
            entry == null ||
            entry.reference != reference ||
            entry.lifecycle != CognitiveTurnLifecycle.COGNITION_READY ||
            entry.inference == null ||
            entry.acceptedCognition == null
        ) {
            return@synchronized CognitiveTurnTransitionResult.Stale
        }
        check(!entry.publicationInProgress) {
            "cognitive turn completion is not allowed from inside publication"
        }
        entry.lifecycle = CognitiveTurnLifecycle.COMPLETED
        current = null
        CognitiveTurnTransitionResult.Transitioned
    }

    internal fun failIfCurrent(
        reference: CognitiveTurnReference,
        reason: CognitiveTurnFailure = CognitiveTurnFailure.TURN_FAILED
    ): CognitiveTurnTransitionResult = synchronized(lock) {
        val entry = current
        if (entry == null || entry.reference != reference || entry.lifecycle == CognitiveTurnLifecycle.COMPLETED || entry.lifecycle == CognitiveTurnLifecycle.FAILED) {
            return@synchronized CognitiveTurnTransitionResult.Stale
        }
        check(!entry.publicationInProgress) {
            "cognitive turn failure is not allowed from inside publication"
        }
        entry.lifecycle = CognitiveTurnLifecycle.FAILED
        current = null
        CognitiveTurnTransitionResult.Failed(reason)
    }

    private fun handle(entry: Entry): CognitiveTurnHandle = object : CognitiveTurnHandle {
        override val reference: CognitiveTurnReference = entry.reference

        override fun isCurrent(): Boolean = synchronized(lock) { current === entry }

        override fun lifecycle(): CognitiveTurnLifecycle = synchronized(lock) { entry.lifecycle }

        override fun toString(): String = "CognitiveTurnHandle(reference=$reference)"
    }

    private fun contextWithinLimits(context: CognitiveContextSnapshot): Boolean {
        if (context.items.size > limits.maxContextItems) return false
        return context.items.all { it.content.length <= limits.maxContextItemChars }
    }

    private fun inferenceWithinLimits(result: CognitiveInferenceResult.Succeeded): Boolean =
        result.output.length <= limits.maxInferenceOutputChars
}
