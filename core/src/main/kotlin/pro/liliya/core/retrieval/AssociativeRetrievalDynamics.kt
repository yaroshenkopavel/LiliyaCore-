package pro.liliya.core.retrieval

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@JvmInline
value class RetrievalAssociationId(val value: String) {
    init {
        require(value.isNotBlank())
        require(value.length <= 96)
    }
}

data class AssociativeRetrievalPolicy(
    val version: Int = CURRENT_VERSION,
    val maxParticipants: Int = DEFAULT_MAX_PARTICIPANTS,
    val strengthenDelta: Double = DEFAULT_STRENGTHEN_DELTA,
    val decayDelta: Double = DEFAULT_DECAY_DELTA,
    val maxWeight: Double = DEFAULT_MAX_WEIGHT,
    val maxScoreBonus: Double = DEFAULT_MAX_SCORE_BONUS
) {
    init {
        require(version == CURRENT_VERSION)
        require(maxParticipants in 2..MAX_PARTICIPANTS)
        require(strengthenDelta.isFinite() && strengthenDelta > 0.0 && strengthenDelta <= 1.0)
        require(decayDelta.isFinite() && decayDelta > 0.0 && decayDelta <= 1.0)
        require(maxWeight.isFinite() && maxWeight > 0.0 && maxWeight <= 1.0)
        require(maxScoreBonus.isFinite() && maxScoreBonus >= 0.0 && maxScoreBonus <= MAX_SCORE_BONUS)
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val DEFAULT_MAX_PARTICIPANTS = 8
        const val MAX_PARTICIPANTS = 8
        const val DEFAULT_STRENGTHEN_DELTA = 0.05
        const val DEFAULT_DECAY_DELTA = 0.01
        const val DEFAULT_MAX_WEIGHT = 1.0
        const val DEFAULT_MAX_SCORE_BONUS = 0.002
        const val MAX_SCORE_BONUS = 0.01
    }
}

data class RetrievalAssociationCandidate(
    val id: RetrievalAssociationId,
    val participants: List<RetrievalCandidateId>,
    val policyVersion: Int,
    val advisoryOnly: Boolean = true
) {
    init {
        require(policyVersion == AssociativeRetrievalPolicy.CURRENT_VERSION)
        require(participants.size in 2..AssociativeRetrievalPolicy.MAX_PARTICIPANTS)
        require(participants.distinct().size == participants.size)
        require(participants.zipWithNext().all { (left, right) ->
            compareUtf8(left.value, right.value) < 0
        })
        require(advisoryOnly)
    }
}

data class RetrievalAssociationState(
    val id: RetrievalAssociationId,
    val participants: List<RetrievalCandidateId>,
    val policyVersion: Int,
    val weight: Double,
    val validatedSuccessCount: Long,
    val lastValidationSequence: Long,
    val lastValidationId: String?,
    val lastValidationVersion: Int?,
    val lastGroundedOutcomeId: String?,
    val advisoryOnly: Boolean = true
) {
    init {
        require(policyVersion == AssociativeRetrievalPolicy.CURRENT_VERSION)
        require(participants.size in 2..AssociativeRetrievalPolicy.MAX_PARTICIPANTS)
        require(participants.distinct().size == participants.size)
        require(participants.zipWithNext().all { (left, right) ->
            compareUtf8(left.value, right.value) < 0
        })
        require(weight.isFinite() && weight in 0.0..1.0)
        require(validatedSuccessCount >= 0L)
        require(lastValidationSequence >= 0L)
        if (lastValidationSequence == 0L) {
            require(lastValidationId == null)
            require(lastValidationVersion == null)
            require(lastGroundedOutcomeId == null)
        } else {
            require(!lastValidationId.isNullOrBlank())
            require(lastValidationVersion != null && lastValidationVersion > 0)
            require(!lastGroundedOutcomeId.isNullOrBlank())
        }
        require(advisoryOnly)
    }
}

data class RetrievalAssociationValidation(
    val associationId: RetrievalAssociationId,
    val participants: List<RetrievalCandidateId>,
    val validationId: String,
    val validationVersion: Int,
    val validationSequence: Long,
    val groundedOutcomeId: String,
    val successful: Boolean
) {
    init {
        require(participants.size in 2..AssociativeRetrievalPolicy.MAX_PARTICIPANTS)
        require(participants.distinct().size == participants.size)
        require(participants.zipWithNext().all { (left, right) ->
            compareUtf8(left.value, right.value) < 0
        })
        require(validationId.isNotBlank() && validationId.length <= 128)
        require(validationVersion > 0)
        require(validationSequence > 0L)
        require(groundedOutcomeId.isNotBlank() && groundedOutcomeId.length <= 256)
    }
}

sealed interface RetrievalAssociationUpdateResult {
    data class Updated(val state: RetrievalAssociationState) : RetrievalAssociationUpdateResult
    data class Unchanged(val state: RetrievalAssociationState) : RetrievalAssociationUpdateResult
    data class Rejected(val reason: String) : RetrievalAssociationUpdateResult {
        init { require(reason.isNotBlank()) }
    }
}

sealed interface RetrievalAssociationRebuildResult {
    data class Rebuilt(val state: RetrievalAssociationState) :
        RetrievalAssociationRebuildResult
    data class Rejected(val reason: String) : RetrievalAssociationRebuildResult {
        init { require(reason.isNotBlank()) }
    }
}

data class AssociativeRerankContribution(
    val associationId: RetrievalAssociationId,
    val weight: Double,
    val scoreBonus: Double
) {
    init {
        require(weight.isFinite() && weight >= 0.0)
        require(scoreBonus.isFinite() && scoreBonus >= 0.0)
    }
}

data class AssociativelyRerankedCandidate(
    val candidate: HybridFusedCandidate,
    val adjustedScore: Double,
    val contributions: List<AssociativeRerankContribution>,
    val requiresCanonicalRevalidation: Boolean = true
) {
    init {
        require(adjustedScore.isFinite() && adjustedScore > 0.0)
        require(requiresCanonicalRevalidation)
    }
}

object AssociativeRetrievalDynamics {
    fun candidateFromCoactivation(
        participantIds: List<RetrievalCandidateId>,
        policy: AssociativeRetrievalPolicy = AssociativeRetrievalPolicy()
    ): RetrievalAssociationCandidate? {
        val ordered = participantIds.distinct().sortedWith { left, right ->
            compareUtf8(left.value, right.value)
        }
        if (ordered.size < 2 || ordered.size > policy.maxParticipants) return null

        return RetrievalAssociationCandidate(
            id = associationId(ordered, policy.version),
            participants = ordered,
            policyVersion = policy.version
        )
    }

    fun initialState(candidate: RetrievalAssociationCandidate): RetrievalAssociationState =
        RetrievalAssociationState(
            id = candidate.id,
            participants = candidate.participants,
            policyVersion = candidate.policyVersion,
            weight = 0.0,
            validatedSuccessCount = 0L,
            lastValidationSequence = 0L,
            lastValidationId = null,
            lastValidationVersion = null,
            lastGroundedOutcomeId = null
        )

    fun applyValidation(
        current: RetrievalAssociationState,
        validation: RetrievalAssociationValidation,
        policy: AssociativeRetrievalPolicy = AssociativeRetrievalPolicy()
    ): RetrievalAssociationUpdateResult {
        if (current.policyVersion != policy.version) {
            return RetrievalAssociationUpdateResult.Rejected(
                "association policy version mismatch"
            )
        }
        if (
            validation.associationId != current.id ||
            validation.participants != current.participants
        ) {
            return RetrievalAssociationUpdateResult.Rejected(
                "association validation identity mismatch"
            )
        }
        if (validation.validationSequence <= current.lastValidationSequence) {
            return RetrievalAssociationUpdateResult.Unchanged(current)
        }
        if (!validation.successful) {
            return RetrievalAssociationUpdateResult.Unchanged(
                current.copy(
                    lastValidationSequence = validation.validationSequence,
                    lastValidationId = validation.validationId,
                    lastValidationVersion = validation.validationVersion,
                    lastGroundedOutcomeId = validation.groundedOutcomeId
                )
            )
        }

        val nextWeight = minOf(policy.maxWeight, current.weight + policy.strengthenDelta)
        val nextCount = try {
            Math.addExact(current.validatedSuccessCount, 1L)
        } catch (_: ArithmeticException) {
            return RetrievalAssociationUpdateResult.Rejected(
                "validated association success count overflow"
            )
        }

        return RetrievalAssociationUpdateResult.Updated(
            current.copy(
                weight = nextWeight,
                validatedSuccessCount = nextCount,
                lastValidationSequence = validation.validationSequence,
                lastValidationId = validation.validationId,
                lastValidationVersion = validation.validationVersion,
                lastGroundedOutcomeId = validation.groundedOutcomeId
            )
        )
    }

    fun rebuild(
        candidate: RetrievalAssociationCandidate,
        validations: List<RetrievalAssociationValidation>,
        policy: AssociativeRetrievalPolicy = AssociativeRetrievalPolicy()
    ): RetrievalAssociationRebuildResult {
        if (validations.size > MAX_VALIDATIONS_PER_REBUILD) {
            return RetrievalAssociationRebuildResult.Rejected(
                "association validation rebuild budget exceeded"
            )
        }
        if (candidate.policyVersion != policy.version) {
            return RetrievalAssociationRebuildResult.Rejected(
                "association candidate policy version mismatch"
            )
        }

        var state = initialState(candidate)
        var previousSequence = 0L
        for (validation in validations) {
            if (validation.validationSequence <= previousSequence) {
                return RetrievalAssociationRebuildResult.Rejected(
                    "association validation rebuild sequence is not strictly increasing"
                )
            }
            if (
                validation.associationId != candidate.id ||
                validation.participants != candidate.participants
            ) {
                return RetrievalAssociationRebuildResult.Rejected(
                    "association validation rebuild identity mismatch"
                )
            }
            state = when (
                val updated = applyValidation(state, validation, policy)
            ) {
                is RetrievalAssociationUpdateResult.Updated -> updated.state
                is RetrievalAssociationUpdateResult.Unchanged -> updated.state
                is RetrievalAssociationUpdateResult.Rejected ->
                    return RetrievalAssociationRebuildResult.Rejected(updated.reason)
            }
            previousSequence = validation.validationSequence
        }
        return RetrievalAssociationRebuildResult.Rebuilt(state)
    }

    fun decay(
        current: RetrievalAssociationState,
        policy: AssociativeRetrievalPolicy = AssociativeRetrievalPolicy()
    ): RetrievalAssociationState {
        require(current.policyVersion == policy.version)
        return current.copy(weight = maxOf(0.0, current.weight - policy.decayDelta))
    }

    fun rerank(
        fused: List<HybridFusedCandidate>,
        associations: List<RetrievalAssociationState>,
        policy: AssociativeRetrievalPolicy = AssociativeRetrievalPolicy()
    ): List<AssociativelyRerankedCandidate> {
        require(fused.size <= HybridRankFusionPolicy.MAX_OUTPUT_CANDIDATES)
        require(associations.size <= MAX_ASSOCIATIONS_PER_RERANK)
        require(fused.map { it.id }.distinct().size == fused.size)

        val present = fused.mapTo(hashSetOf()) { it.id }
        val contributionsByCandidate =
            HashMap<RetrievalCandidateId, MutableList<AssociativeRerankContribution>>()

        associations
            .asSequence()
            .filter { it.policyVersion == policy.version }
            .filter { it.weight > 0.0 }
            .filter { association -> association.participants.all(present::contains) }
            .forEach { association ->
                val bonus = association.weight * policy.maxScoreBonus
                association.participants.forEach { participant ->
                    contributionsByCandidate
                        .getOrPut(participant) { ArrayList() }
                        .add(
                            AssociativeRerankContribution(
                                association.id,
                                association.weight,
                                bonus
                            )
                        )
                }
            }

        return fused.map { candidate ->
            val contributions = contributionsByCandidate[candidate.id]
                .orEmpty()
                .sortedWith { left, right ->
                    compareUtf8(left.associationId.value, right.associationId.value)
                }
            val totalBonus = contributions.sumOf { it.scoreBonus }
                .coerceAtMost(policy.maxScoreBonus)
            AssociativelyRerankedCandidate(
                candidate = candidate,
                adjustedScore = candidate.fusedScore + totalBonus,
                contributions = contributions
            )
        }.sortedWith { left, right ->
            val score = right.adjustedScore.compareTo(left.adjustedScore)
            if (score != 0) score
            else compareUtf8(left.candidate.id.value, right.candidate.id.value)
        }
    }

    private fun associationId(
        participants: List<RetrievalCandidateId>,
        policyVersion: Int
    ): RetrievalAssociationId {
        val digest = MessageDigest.getInstance("SHA-256")
        put(digest, "retrieval-association-v1")
        put(digest, policyVersion.toString())
        participants.forEach { put(digest, it.value) }
        return RetrievalAssociationId(
            "retrieval-association-" + digest.digest().joinToString("") {
                "%02x".format(it)
            }
        )
    }

    private fun put(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }

    const val MAX_ASSOCIATIONS_PER_RERANK = 128
    const val MAX_VALIDATIONS_PER_REBUILD = 128
}
