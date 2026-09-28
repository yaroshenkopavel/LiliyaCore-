package pro.liliya.core.retrieval

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@JvmInline
value class AssociativeRetrievalAssociationId(val value: String) {
    init {
        require(value.isNotBlank())
        require(value.length <= MAX_LENGTH)
    }

    companion object {
        const val MAX_LENGTH = 80
    }
}

@JvmInline
value class AssociativeValidationSequence(val value: Long) {
    init { require(value >= 0L) }
}

@JvmInline
value class AssociativeValidationEvidenceId(val value: String) {
    init {
        require(value.isNotBlank())
        require(value.length <= MAX_LENGTH)
    }

    companion object { const val MAX_LENGTH = 256 }
}
data class AssociativeRetrievalPair(
    val left: RetrievalCandidateId,
    val right: RetrievalCandidateId
) {
    init {
        require(left != right)
        require(compareUtf8(left.value, right.value) < 0)
    }

    val id: AssociativeRetrievalAssociationId = AssociativeRetrievalIds.id(this)

    companion object {
        fun of(
            first: RetrievalCandidateId,
            second: RetrievalCandidateId
        ): AssociativeRetrievalPair {
            require(first != second) { "association endpoints must be distinct" }
            return if (compareUtf8(first.value, second.value) < 0) {
                AssociativeRetrievalPair(first, second)
            } else {
                AssociativeRetrievalPair(second, first)
            }
        }
    }
}

data class AssociativeRetrievalPolicy(
    val version: Int = CURRENT_VERSION,
    val maxCandidateObservations: Int = DEFAULT_MAX_CANDIDATE_OBSERVATIONS,
    val maxStrengthUnits: Int = DEFAULT_MAX_STRENGTH_UNITS,
    val strengthenStepUnits: Int = DEFAULT_STRENGTHEN_STEP_UNITS,
    val decayStepUnits: Int = DEFAULT_DECAY_STEP_UNITS,
    val maxDecayStepsPerCall: Int = DEFAULT_MAX_DECAY_STEPS_PER_CALL
) {
    init {
        require(version == CURRENT_VERSION)
        require(maxCandidateObservations in 1..MAX_CANDIDATE_OBSERVATIONS)
        require(maxStrengthUnits in 1..MAX_STRENGTH_UNITS)
        require(strengthenStepUnits in 1..maxStrengthUnits)
        require(decayStepUnits in 1..maxStrengthUnits)
        require(maxDecayStepsPerCall in 1..MAX_DECAY_STEPS_PER_CALL)
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val DEFAULT_MAX_CANDIDATE_OBSERVATIONS = 1024
        const val MAX_CANDIDATE_OBSERVATIONS = 1_000_000
        const val DEFAULT_MAX_STRENGTH_UNITS = 1000
        const val MAX_STRENGTH_UNITS = 10_000
        const val DEFAULT_STRENGTHEN_STEP_UNITS = 50
        const val DEFAULT_DECAY_STEP_UNITS = 10
        const val DEFAULT_MAX_DECAY_STEPS_PER_CALL = 100
        const val MAX_DECAY_STEPS_PER_CALL = 10_000
    }
}

data class AssociativeRetrievalState(
    val version: Int = CURRENT_VERSION,
    val pair: AssociativeRetrievalPair,
    val candidateObservationCount: Int,
    val validatedStrengthUnits: Int,
    val lastValidationSequence: AssociativeValidationSequence? = null,
    val lastValidationEvidenceId: AssociativeValidationEvidenceId? = null,
    val advisoryOnly: Boolean = true
) {
    init {
        require(version == CURRENT_VERSION)
        require(candidateObservationCount in 0..AssociativeRetrievalPolicy.MAX_CANDIDATE_OBSERVATIONS)
        require(validatedStrengthUnits in 0..AssociativeRetrievalPolicy.MAX_STRENGTH_UNITS)
        require((lastValidationSequence == null) == (lastValidationEvidenceId == null))
        require(advisoryOnly)
    }

    companion object { const val CURRENT_VERSION = 1 }
}

sealed interface AssociativeRetrievalSignal {
    val pair: AssociativeRetrievalPair

    data class CoActivated(
        override val pair: AssociativeRetrievalPair
    ) : AssociativeRetrievalSignal

    data class ValidatedSuccess(
        override val pair: AssociativeRetrievalPair,
        val sequence: AssociativeValidationSequence,
        val evidenceId: AssociativeValidationEvidenceId
    ) : AssociativeRetrievalSignal

    data class Decay(
        override val pair: AssociativeRetrievalPair,
        val steps: Int
    ) : AssociativeRetrievalSignal {
        init { require(steps > 0) }
    }

    data class Reset(
        override val pair: AssociativeRetrievalPair
    ) : AssociativeRetrievalSignal
}
enum class AssociativeRetrievalOperation {
    CANDIDATE_OBSERVED,
    VALIDATED_STRENGTHEN,
    DECAY,
    RESET
}

data class AssociativeRetrievalAudit(
    val associationId: AssociativeRetrievalAssociationId,
    val pair: AssociativeRetrievalPair,
    val operation: AssociativeRetrievalOperation,
    val priorObservationCount: Int,
    val newObservationCount: Int,
    val priorStrengthUnits: Int,
    val newStrengthUnits: Int,
    val validationSequence: AssociativeValidationSequence? = null,
    val validationEvidenceId: AssociativeValidationEvidenceId? = null,
    val advisoryOnly: Boolean = true
) {
    init {
        require(associationId == pair.id)
        require(priorObservationCount >= 0)
        require(newObservationCount >= 0)
        require(priorStrengthUnits >= 0)
        require(newStrengthUnits >= 0)
        require((validationSequence == null) == (validationEvidenceId == null))
        require(advisoryOnly)
    }
}

sealed interface AssociativeRetrievalTransitionResult {
    data class Updated(
        val state: AssociativeRetrievalState,
        val audit: AssociativeRetrievalAudit
    ) : AssociativeRetrievalTransitionResult

    data class NoChange(
        val state: AssociativeRetrievalState,
        val reason: String,
        val audit: AssociativeRetrievalAudit
    ) : AssociativeRetrievalTransitionResult {
        init { require(reason.isNotBlank()) }
    }

    data class Rejected(
        val associationId: AssociativeRetrievalAssociationId,
        val pair: AssociativeRetrievalPair,
        val operation: AssociativeRetrievalOperation,
        val reason: String,
        val advisoryOnly: Boolean = true
    ) : AssociativeRetrievalTransitionResult {
        init {
            require(associationId == pair.id)
            require(reason.isNotBlank())
            require(advisoryOnly)
        }
    }
}

object DeterministicAssociativeRetrievalStateMachine {
    fun apply(
        current: AssociativeRetrievalState?,
        signal: AssociativeRetrievalSignal,
        policy: AssociativeRetrievalPolicy = AssociativeRetrievalPolicy()
    ): AssociativeRetrievalTransitionResult {
        val operation = signal.operation()
        if (current != null && current.pair != signal.pair) {
            return rejected(signal.pair, operation, "association state pair mismatch")
        }

        return when (signal) {
            is AssociativeRetrievalSignal.CoActivated ->
                observe(current, signal.pair, policy)
            is AssociativeRetrievalSignal.ValidatedSuccess ->
                strengthen(current, signal, policy)
            is AssociativeRetrievalSignal.Decay ->
                decay(current, signal, policy)
            is AssociativeRetrievalSignal.Reset ->
                reset(current, signal.pair)
        }
    }

    private fun observe(
        current: AssociativeRetrievalState?,
        pair: AssociativeRetrievalPair,
        policy: AssociativeRetrievalPolicy
    ): AssociativeRetrievalTransitionResult {
        val prior = current ?: emptyState(pair)
        val nextCount = minOf(
            policy.maxCandidateObservations,
            prior.candidateObservationCount + 1
        )
        val next = prior.copy(candidateObservationCount = nextCount)
        val audit = audit(
            prior,
            next,
            AssociativeRetrievalOperation.CANDIDATE_OBSERVED
        )
        return if (next == prior) {
            AssociativeRetrievalTransitionResult.NoChange(
                prior,
                "candidate observation count is saturated",
                audit
            )
        } else {
            AssociativeRetrievalTransitionResult.Updated(next, audit)
        }
    }

    private fun strengthen(
        current: AssociativeRetrievalState?,
        signal: AssociativeRetrievalSignal.ValidatedSuccess,
        policy: AssociativeRetrievalPolicy
    ): AssociativeRetrievalTransitionResult {
        val state = current ?: return rejected(
            signal.pair,
            AssociativeRetrievalOperation.VALIDATED_STRENGTHEN,
            "validated strengthening requires an observed candidate association"
        )
        if (state.candidateObservationCount == 0) {
            return rejected(
                signal.pair,
                AssociativeRetrievalOperation.VALIDATED_STRENGTHEN,
                "validated strengthening requires an observed candidate association"
            )
        }

        val last = state.lastValidationSequence
        if (last != null && signal.sequence.value <= last.value) {
            val audit = audit(
                state,
                state,
                AssociativeRetrievalOperation.VALIDATED_STRENGTHEN,
                signal.sequence,
                signal.evidenceId
            )
            return AssociativeRetrievalTransitionResult.NoChange(
                state,
                "validation sequence is duplicate or stale",
                audit
            )
        }

        val nextStrength = minOf(
            policy.maxStrengthUnits,
            state.validatedStrengthUnits + policy.strengthenStepUnits
        )
        val next = state.copy(
            validatedStrengthUnits = nextStrength,
            lastValidationSequence = signal.sequence,
            lastValidationEvidenceId = signal.evidenceId
        )
        return AssociativeRetrievalTransitionResult.Updated(
            next,
            audit(
                state,
                next,
                AssociativeRetrievalOperation.VALIDATED_STRENGTHEN,
                signal.sequence,
                signal.evidenceId
            )
        )
    }
    private fun decay(
        current: AssociativeRetrievalState?,
        signal: AssociativeRetrievalSignal.Decay,
        policy: AssociativeRetrievalPolicy
    ): AssociativeRetrievalTransitionResult {
        val state = current ?: return rejected(
            signal.pair,
            AssociativeRetrievalOperation.DECAY,
            "decay requires existing associative state"
        )
        if (signal.steps > policy.maxDecayStepsPerCall) {
            return rejected(
                signal.pair,
                AssociativeRetrievalOperation.DECAY,
                "decay step budget exceeded"
            )
        }
        val delta = policy.decayStepUnits.toLong() * signal.steps.toLong()
        val nextStrength = maxOf(
            0,
            (state.validatedStrengthUnits.toLong() - delta)
                .coerceAtLeast(0L)
                .toInt()
        )
        val next = state.copy(validatedStrengthUnits = nextStrength)
        val audit = audit(state, next, AssociativeRetrievalOperation.DECAY)
        return if (next == state) {
            AssociativeRetrievalTransitionResult.NoChange(
                state,
                "association strength is already zero",
                audit
            )
        } else {
            AssociativeRetrievalTransitionResult.Updated(next, audit)
        }
    }

    private fun reset(
        current: AssociativeRetrievalState?,
        pair: AssociativeRetrievalPair
    ): AssociativeRetrievalTransitionResult {
        val state = current ?: return rejected(
            pair,
            AssociativeRetrievalOperation.RESET,
            "reset requires existing associative state"
        )
        val next = emptyState(pair)
        val audit = audit(state, next, AssociativeRetrievalOperation.RESET)
        return if (next == state) {
            AssociativeRetrievalTransitionResult.NoChange(
                state,
                "associative state is already reset",
                audit
            )
        } else {
            AssociativeRetrievalTransitionResult.Updated(next, audit)
        }
    }

    private fun emptyState(pair: AssociativeRetrievalPair) =
        AssociativeRetrievalState(
            pair = pair,
            candidateObservationCount = 0,
            validatedStrengthUnits = 0
        )

    private fun rejected(
        pair: AssociativeRetrievalPair,
        operation: AssociativeRetrievalOperation,
        reason: String
    ) = AssociativeRetrievalTransitionResult.Rejected(
        associationId = pair.id,
        pair = pair,
        operation = operation,
        reason = reason
    )

    private fun audit(
        prior: AssociativeRetrievalState,
        next: AssociativeRetrievalState,
        operation: AssociativeRetrievalOperation,
        sequence: AssociativeValidationSequence? = null,
        evidenceId: AssociativeValidationEvidenceId? = null
    ) = AssociativeRetrievalAudit(
        associationId = prior.pair.id,
        pair = prior.pair,
        operation = operation,
        priorObservationCount = prior.candidateObservationCount,
        newObservationCount = next.candidateObservationCount,
        priorStrengthUnits = prior.validatedStrengthUnits,
        newStrengthUnits = next.validatedStrengthUnits,
        validationSequence = sequence,
        validationEvidenceId = evidenceId
    )

    private fun AssociativeRetrievalSignal.operation(): AssociativeRetrievalOperation =
        when (this) {
            is AssociativeRetrievalSignal.CoActivated ->
                AssociativeRetrievalOperation.CANDIDATE_OBSERVED
            is AssociativeRetrievalSignal.ValidatedSuccess ->
                AssociativeRetrievalOperation.VALIDATED_STRENGTHEN
            is AssociativeRetrievalSignal.Decay ->
                AssociativeRetrievalOperation.DECAY
            is AssociativeRetrievalSignal.Reset ->
                AssociativeRetrievalOperation.RESET
        }
}

private object AssociativeRetrievalIds {
    fun id(pair: AssociativeRetrievalPair): AssociativeRetrievalAssociationId {
        val digest = MessageDigest.getInstance("SHA-256")
        put(digest, "associative-retrieval-v1")
        put(digest, pair.left.value)
        put(digest, pair.right.value)
        return AssociativeRetrievalAssociationId(
            "assoc-" + digest.digest().joinToString("") { "%02x".format(it) }
        )
    }

    private fun put(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        try {
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        } finally {
            bytes.fill(0)
        }
    }
}
