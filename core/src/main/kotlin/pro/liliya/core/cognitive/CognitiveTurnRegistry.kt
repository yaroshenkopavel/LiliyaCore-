package pro.liliya.core.cognitive

import java.util.concurrent.atomic.AtomicLong

sealed interface CognitiveTurnRegistrationResult {
    data class Registered(val ownership: CognitiveTurnOwnership) : CognitiveTurnRegistrationResult
    data class Rejected(val reason: CognitiveTurnRegistrationFailure) : CognitiveTurnRegistrationResult
}

enum class CognitiveTurnRegistrationFailure {
    LIVE_TURN_EXISTS,
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
    data class Rejected(val reason: CognitiveTurnFailure) : CognitiveTurnTransitionResult
}

interface CognitiveTurnOwnership {
    val reference: CognitiveTurnReference
    val input: CognitiveInput

    fun isCurrent(): Boolean
    fun lifecycle(): CognitiveTurnLifecycle
    fun context(): CognitiveContextSnapshot?
    fun inference(): CognitiveInferenceResult?

    fun publishContextIfCurrent(
        context: CognitiveContextSnapshot,
        publish: () -> Unit = {}
    ): CognitiveTurnPublicationResult

    fun beginGenerating(): CognitiveTurnTransitionResult

    fun publishInferenceIfCurrent(
        result: CognitiveInferenceResult,
        publish: () -> Unit = {}
    ): CognitiveTurnPublicationResult

    fun complete(): CognitiveTurnTransitionResult
    fun fail(reason: CognitiveTurnFailure = CognitiveTurnFailure.TURN_FAILED): CognitiveTurnTransitionResult
}

class CognitiveTurnRegistry internal constructor(
    private val limits: CognitiveRuntimeLimits,
    initialGeneration: Long = 0L
) {
    private data class Entry(
        val reference: CognitiveTurnReference,
        val input: CognitiveInput,
        var lifecycle: CognitiveTurnLifecycle = CognitiveTurnLifecycle.CREATED,
        var context: CognitiveContextSnapshot? = null,
        var inference: CognitiveInferenceResult? = null,
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
        CognitiveTurnRegistrationResult.Registered(ownership(entry))
    }

    fun currentReference(): CognitiveTurnReference? = synchronized(lock) { current?.reference }
    fun currentLifecycle(): CognitiveTurnLifecycle? = synchronized(lock) { current?.lifecycle }

    private fun ownership(entry: Entry): CognitiveTurnOwnership = object : CognitiveTurnOwnership {
        override val reference: CognitiveTurnReference = entry.reference
        override val input: CognitiveInput = entry.input

        override fun isCurrent(): Boolean = synchronized(lock) { current === entry }
        override fun lifecycle(): CognitiveTurnLifecycle = synchronized(lock) { entry.lifecycle }
        override fun context(): CognitiveContextSnapshot? = synchronized(lock) { entry.context }
        override fun inference(): CognitiveInferenceResult? = synchronized(lock) { entry.inference }

        override fun publishContextIfCurrent(
            context: CognitiveContextSnapshot,
            publish: () -> Unit
        ): CognitiveTurnPublicationResult = synchronized(lock) {
            if (current !== entry || entry.lifecycle != CognitiveTurnLifecycle.CREATED) {
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

        override fun beginGenerating(): CognitiveTurnTransitionResult = synchronized(lock) {
            if (current !== entry || entry.lifecycle != CognitiveTurnLifecycle.CONTEXT_READY) {
                return@synchronized CognitiveTurnTransitionResult.Stale
            }
            check(!entry.publicationInProgress) {
                "cognitive generation transition is not allowed from inside publication"
            }
            entry.lifecycle = CognitiveTurnLifecycle.GENERATING
            CognitiveTurnTransitionResult.Transitioned
        }

        override fun publishInferenceIfCurrent(
            result: CognitiveInferenceResult,
            publish: () -> Unit
        ): CognitiveTurnPublicationResult = synchronized(lock) {
            if (current !== entry || entry.lifecycle != CognitiveTurnLifecycle.GENERATING) {
                return@synchronized CognitiveTurnPublicationResult.Stale
            }
            if (result.turn != entry.reference || !inferenceWithinLimits(result)) {
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
                    entry.inference = result
                    entry.lifecycle = CognitiveTurnLifecycle.COGNITION_READY
                    CognitiveTurnPublicationResult.Published
                }
            } catch (throwable: Throwable) {
                CognitiveTurnPublicationResult.Failed(throwable)
            } finally {
                entry.publicationInProgress = false
            }
        }

        override fun complete(): CognitiveTurnTransitionResult = synchronized(lock) {
            if (current !== entry || entry.lifecycle != CognitiveTurnLifecycle.COGNITION_READY) {
                return@synchronized CognitiveTurnTransitionResult.Stale
            }
            check(!entry.publicationInProgress) {
                "cognitive turn completion is not allowed from inside publication"
            }
            entry.lifecycle = CognitiveTurnLifecycle.COMPLETED
            current = null
            CognitiveTurnTransitionResult.Transitioned
        }

        override fun fail(reason: CognitiveTurnFailure): CognitiveTurnTransitionResult = synchronized(lock) {
            if (current !== entry || entry.lifecycle == CognitiveTurnLifecycle.COMPLETED || entry.lifecycle == CognitiveTurnLifecycle.FAILED) {
                return@synchronized CognitiveTurnTransitionResult.Stale
            }
            check(!entry.publicationInProgress) {
                "cognitive turn failure is not allowed from inside publication"
            }
            entry.lifecycle = CognitiveTurnLifecycle.FAILED
            current = null
            CognitiveTurnTransitionResult.Rejected(reason)
        }
    }

    private fun contextWithinLimits(context: CognitiveContextSnapshot): Boolean {
        if (context.items.size > limits.maxContextItems) return false
        return context.items.all { it.content.length <= limits.maxContextItemChars }
    }

    private fun inferenceWithinLimits(result: CognitiveInferenceResult): Boolean = when (result) {
        is CognitiveInferenceResult.Succeeded -> result.output.length <= limits.maxInferenceOutputChars
        is CognitiveInferenceResult.Rejected -> true
    }
}
