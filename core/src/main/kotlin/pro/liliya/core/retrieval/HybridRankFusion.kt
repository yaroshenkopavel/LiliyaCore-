package pro.liliya.core.retrieval

import java.nio.charset.StandardCharsets

@JvmInline
value class RetrievalCandidateId(val value: String) {
    init {
        require(value.isNotBlank()) { "retrieval candidate id must not be blank" }
        require(value.length <= MAX_LENGTH) { "retrieval candidate id is too long" }
    }
    companion object { const val MAX_LENGTH = 256 }
}

@JvmInline
value class RetrievalChannelId(val value: String) {
    init {
        require(value.isNotBlank()) { "retrieval channel id must not be blank" }
        require(value.length <= MAX_LENGTH) { "retrieval channel id is too long" }
    }
    companion object { const val MAX_LENGTH = 64 }
}

enum class RetrievalChannelRequirement { REQUIRED, OPTIONAL }

data class RankedRetrievalCandidate(val id: RetrievalCandidateId)

sealed interface RetrievalChannelResult {
    val channelId: RetrievalChannelId
    val requirement: RetrievalChannelRequirement

    data class Ranked(
        override val channelId: RetrievalChannelId,
        override val requirement: RetrievalChannelRequirement,
        val candidates: List<RankedRetrievalCandidate>
    ) : RetrievalChannelResult {
        init {
            require(candidates.size <= HybridRankFusionPolicy.MAX_PER_CHANNEL_CANDIDATES) {
                "retrieval channel candidate budget exceeded"
            }
            require(candidates.map { it.id }.distinct().size == candidates.size) {
                "retrieval channel must not contain duplicate candidate ids"
            }
        }
    }

    data class Unavailable(
        override val channelId: RetrievalChannelId,
        override val requirement: RetrievalChannelRequirement,
        val reason: String
    ) : RetrievalChannelResult {
        init { require(reason.isNotBlank()) }
    }

    data class Failed(
        override val channelId: RetrievalChannelId,
        override val requirement: RetrievalChannelRequirement,
        val reason: String
    ) : RetrievalChannelResult {
        init { require(reason.isNotBlank()) }
    }
}

data class HybridRankFusionPolicy(
    val version: Int = CURRENT_VERSION,
    val reciprocalRankConstant: Int = DEFAULT_RRF_K,
    val maxOutputCandidates: Int
) {
    init {
        require(version == CURRENT_VERSION)
        require(reciprocalRankConstant in 1..MAX_RRF_K)
        require(maxOutputCandidates in 1..MAX_OUTPUT_CANDIDATES)
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val DEFAULT_RRF_K = 60
        const val MAX_RRF_K = 10_000
        const val MAX_CHANNELS = 8
        const val MAX_PER_CHANNEL_CANDIDATES = 128
        const val MAX_OUTPUT_CANDIDATES = 128
    }
}

data class HybridRankContribution(
    val channelId: RetrievalChannelId,
    val rank: Int,
    val reciprocalContribution: Double
) {
    init {
        require(rank > 0)
        require(reciprocalContribution.isFinite() && reciprocalContribution > 0.0)
    }
}

data class HybridFusedCandidate(
    val id: RetrievalCandidateId,
    val fusedScore: Double,
    val contributions: List<HybridRankContribution>,
    val requiresCanonicalRevalidation: Boolean = true
) {
    init {
        require(fusedScore.isFinite() && fusedScore > 0.0)
        require(contributions.isNotEmpty())
        require(contributions.zipWithNext().all { (left, right) ->
            compareUtf8(left.channelId.value, right.channelId.value) < 0
        })
        require(requiresCanonicalRevalidation)
    }
}

data class HybridRankFusionChannelAudit(
    val channelId: RetrievalChannelId,
    val requirement: RetrievalChannelRequirement,
    val inputCount: Int,
    val used: Boolean,
    val status: String
) {
    init {
        require(inputCount >= 0)
        require(status.isNotBlank())
    }
}

data class HybridRankFusionAudit(
    val policyVersion: Int,
    val reciprocalRankConstant: Int,
    val perChannelBudget: Int,
    val maxOutputCandidates: Int,
    val channels: List<HybridRankFusionChannelAudit>,
    val distinctInputCandidates: Int,
    val outputCount: Int,
    val advisoryOnly: Boolean = true
) {
    init {
        require(policyVersion == HybridRankFusionPolicy.CURRENT_VERSION)
        require(perChannelBudget == HybridRankFusionPolicy.MAX_PER_CHANNEL_CANDIDATES)
        require(distinctInputCandidates >= 0)
        require(outputCount >= 0)
        require(advisoryOnly)
    }
}

sealed interface HybridRankFusionResult {
    data class Fused(
        val candidates: List<HybridFusedCandidate>,
        val audit: HybridRankFusionAudit
    ) : HybridRankFusionResult

    data class FallbackRequired(
        val reason: String,
        val audit: HybridRankFusionAudit
    ) : HybridRankFusionResult {
        init { require(reason.isNotBlank()) }
    }

    data class Rejected(val reason: String) : HybridRankFusionResult {
        init { require(reason.isNotBlank()) }
    }
}

object DeterministicReciprocalRankFusion {
    fun fuse(
        channels: List<RetrievalChannelResult>,
        policy: HybridRankFusionPolicy
    ): HybridRankFusionResult {
        if (channels.isEmpty()) {
            return HybridRankFusionResult.Rejected(
                "hybrid rank fusion requires at least one retrieval channel"
            )
        }
        if (channels.size > HybridRankFusionPolicy.MAX_CHANNELS) {
            return HybridRankFusionResult.Rejected(
                "hybrid rank fusion channel budget exceeded"
            )
        }
        if (channels.map { it.channelId }.distinct().size != channels.size) {
            return HybridRankFusionResult.Rejected(
                "hybrid rank fusion requires unique channel ids"
            )
        }

        val orderedChannels = channels.sortedWith { left, right ->
            compareUtf8(left.channelId.value, right.channelId.value)
        }
        val auditChannels = ArrayList<HybridRankFusionChannelAudit>(orderedChannels.size)
        val contributionsByCandidate =
            java.util.TreeMap<RetrievalCandidateId, MutableList<HybridRankContribution>>(
                Comparator { left, right -> compareUtf8(left.value, right.value) }
            )
        var requiredFailure: String? = null

        for (channel in orderedChannels) {
            when (channel) {
                is RetrievalChannelResult.Ranked -> {
                    auditChannels += HybridRankFusionChannelAudit(
                        channel.channelId,
                        channel.requirement,
                        channel.candidates.size,
                        true,
                        "RANKED"
                    )
                    channel.candidates.forEachIndexed { index, candidate ->
                        val rank = index + 1
                        contributionsByCandidate
                            .getOrPut(candidate.id) { ArrayList() }
                            .add(
                                HybridRankContribution(
                                    channel.channelId,
                                    rank,
                                    1.0 / (policy.reciprocalRankConstant + rank).toDouble()
                                )
                            )
                    }
                }
                is RetrievalChannelResult.Unavailable -> {
                    auditChannels += HybridRankFusionChannelAudit(
                        channel.channelId, channel.requirement, 0, false,
                        "UNAVAILABLE:" + channel.reason
                    )
                    if (channel.requirement == RetrievalChannelRequirement.REQUIRED) {
                        requiredFailure = requiredFailure ?:
                            "required retrieval channel unavailable: " + channel.channelId.value
                    }
                }
                is RetrievalChannelResult.Failed -> {
                    auditChannels += HybridRankFusionChannelAudit(
                        channel.channelId, channel.requirement, 0, false,
                        "FAILED:" + channel.reason
                    )
                    if (channel.requirement == RetrievalChannelRequirement.REQUIRED) {
                        requiredFailure = requiredFailure ?:
                            "required retrieval channel failed: " + channel.channelId.value
                    }
                }
            }
        }

        fun audit(outputCount: Int) = HybridRankFusionAudit(
            policy.version,
            policy.reciprocalRankConstant,
            HybridRankFusionPolicy.MAX_PER_CHANNEL_CANDIDATES,
            policy.maxOutputCandidates,
            auditChannels,
            contributionsByCandidate.size,
            outputCount
        )

        if (requiredFailure != null) {
            return HybridRankFusionResult.FallbackRequired(requiredFailure, audit(0))
        }

        val fused = contributionsByCandidate.map { (id, raw) ->
            val contributions = raw.sortedWith { left, right ->
                compareUtf8(left.channelId.value, right.channelId.value)
            }
            var score = 0.0
            contributions.forEach { score += it.reciprocalContribution }
            HybridFusedCandidate(id, score, contributions)
        }.sortedWith { left, right ->
            val scoreCompare = right.fusedScore.compareTo(left.fusedScore)
            if (scoreCompare != 0) scoreCompare
            else compareUtf8(left.id.value, right.id.value)
        }.take(policy.maxOutputCandidates)

        return HybridRankFusionResult.Fused(fused, audit(fused.size))
    }
}

internal fun compareUtf8(left: String, right: String): Int {
    val leftBytes = left.toByteArray(StandardCharsets.UTF_8)
    val rightBytes = right.toByteArray(StandardCharsets.UTF_8)
    try {
        val common = minOf(leftBytes.size, rightBytes.size)
        for (index in 0 until common) {
            val l = leftBytes[index].toInt() and 0xFF
            val r = rightBytes[index].toInt() and 0xFF
            if (l != r) return l.compareTo(r)
        }
        return leftBytes.size.compareTo(rightBytes.size)
    } finally {
        leftBytes.fill(0)
        rightBytes.fill(0)
    }
}
