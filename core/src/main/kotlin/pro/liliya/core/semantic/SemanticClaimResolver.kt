package pro.liliya.core.semantic

import java.time.Instant

enum class SemanticResolutionOperator {
    CURRENT_VALUE,
    HISTORICAL_VALUE,
    PREVIOUS_VALUE
}

enum class SemanticClaimCardinality {
    SINGLE,
    MULTI
}

enum class SemanticConflictReason {
    MULTIPLE_ACTIVE_VALUES,
    AMBIGUOUS_SAME_VERSION,
    MIXED_CONFLICT_GROUP
}

data class SemanticResolutionQuery(
    val conflictGroupId: SemanticClaimConflictGroupId,
    val operator: SemanticResolutionOperator,
    val worldTime: Instant,
    val knowledgeTime: Instant,
    val cardinality: SemanticClaimCardinality
)

data class SemanticResolutionAudit(
    val operator: SemanticResolutionOperator,
    val conflictGroupId: SemanticClaimConflictGroupId,
    val worldTime: Instant,
    val knowledgeTime: Instant,
    val inputCount: Int,
    val visibleVersionCount: Int,
    val temporalCandidateCount: Int,
    val selectedClaimIds: List<SemanticClaimId>,
    val conflictReason: SemanticConflictReason? = null
)

sealed interface SemanticResolutionResult {
    data class Resolved(
        val claims: List<SemanticClaimRecord>,
        val audit: SemanticResolutionAudit
    ) : SemanticResolutionResult

    data class Conflicted(
        val candidates: List<SemanticClaimRecord>,
        val reason: SemanticConflictReason,
        val audit: SemanticResolutionAudit
    ) : SemanticResolutionResult

    data class Missing(
        val audit: SemanticResolutionAudit
    ) : SemanticResolutionResult
}

object DeterministicSemanticClaimResolver {
    fun resolve(
        records: List<SemanticClaimRecord>,
        query: SemanticResolutionQuery
    ): SemanticResolutionResult {
        if (records.any { SemanticClaimIds.forConflictGroup(it.identity) != query.conflictGroupId }) {
            return conflict(
                candidates = records.sortedWith(recordOrder),
                query = query,
                visibleCount = 0,
                temporalCount = 0,
                reason = SemanticConflictReason.MIXED_CONFLICT_GROUP
            )
        }

        val visible = visibleVersions(records, query)
        if (visible is VersionSelection.Conflict) {
            return conflict(
                candidates = visible.records,
                query = query,
                visibleCount = visible.records.size,
                temporalCount = 0,
                reason = SemanticConflictReason.AMBIGUOUS_SAME_VERSION
            )
        }
        val canonical = (visible as VersionSelection.Selected).records

        val temporal = when (query.operator) {
            SemanticResolutionOperator.CURRENT_VALUE,
            SemanticResolutionOperator.HISTORICAL_VALUE ->
                canonical.filter { it.isValidAt(query.worldTime) }

            SemanticResolutionOperator.PREVIOUS_VALUE ->
                previousCandidates(canonical, query.worldTime)
        }.sortedWith(recordOrder)

        if (temporal.isEmpty()) {
            return SemanticResolutionResult.Missing(
                audit(
                    query = query,
                    inputCount = records.size,
                    visibleCount = canonical.size,
                    temporal = emptyList()
                )
            )
        }

        if (query.cardinality == SemanticClaimCardinality.SINGLE && temporal.size > 1) {
            return conflict(
                candidates = temporal,
                query = query,
                visibleCount = canonical.size,
                temporalCount = temporal.size,
                reason = SemanticConflictReason.MULTIPLE_ACTIVE_VALUES
            )
        }

        return SemanticResolutionResult.Resolved(
            claims = temporal,
            audit = audit(
                query = query,
                inputCount = records.size,
                visibleCount = canonical.size,
                temporal = temporal
            )
        )
    }

    private sealed interface VersionSelection {
        data class Selected(val records: List<SemanticClaimRecord>) : VersionSelection
        data class Conflict(val records: List<SemanticClaimRecord>) : VersionSelection
    }

    private fun visibleVersions(
        records: List<SemanticClaimRecord>,
        query: SemanticResolutionQuery
    ): VersionSelection {
        val visible = records
            .filter { it.temporal.observedAt <= query.knowledgeTime }
            .filter {
                it.temporal.supersededAt == null ||
                    it.temporal.supersededAt > query.knowledgeTime
            }

        val selected = mutableListOf<SemanticClaimRecord>()
        for ((_, sameClaim) in visible.groupBy { it.id }) {
            val maxVersion = sameClaim.maxOf { it.version.value }
            val winners = sameClaim
                .filter { it.version.value == maxVersion }
                .distinct()
            if (winners.size > 1) {
                return VersionSelection.Conflict(winners.sortedWith(recordOrder))
            }
            winners.singleOrNull()?.let(selected::add)
        }
        return VersionSelection.Selected(selected.sortedWith(recordOrder))
    }

    private fun previousCandidates(
        records: List<SemanticClaimRecord>,
        worldTime: Instant
    ): List<SemanticClaimRecord> {
        val ended = records.filter {
            val until = it.temporal.validUntil
            until != null && until <= worldTime
        }
        val latestBoundary = ended.maxOfOrNull { it.temporal.validUntil!! }
            ?: return emptyList()
        return ended.filter { it.temporal.validUntil == latestBoundary }
    }

    private fun SemanticClaimRecord.isValidAt(worldTime: Instant): Boolean {
        val from = temporal.validFrom
        val until = temporal.validUntil
        return (from == null || from <= worldTime) &&
            (until == null || worldTime < until)
    }

    private fun conflict(
        candidates: List<SemanticClaimRecord>,
        query: SemanticResolutionQuery,
        visibleCount: Int,
        temporalCount: Int,
        reason: SemanticConflictReason
    ): SemanticResolutionResult.Conflicted =
        SemanticResolutionResult.Conflicted(
            candidates = candidates,
            reason = reason,
            audit = SemanticResolutionAudit(
                operator = query.operator,
                conflictGroupId = query.conflictGroupId,
                worldTime = query.worldTime,
                knowledgeTime = query.knowledgeTime,
                inputCount = candidates.size,
                visibleVersionCount = visibleCount,
                temporalCandidateCount = temporalCount,
                selectedClaimIds = candidates.map { it.id }.distinct(),
                conflictReason = reason
            )
        )

    private fun audit(
        query: SemanticResolutionQuery,
        inputCount: Int,
        visibleCount: Int,
        temporal: List<SemanticClaimRecord>
    ): SemanticResolutionAudit =
        SemanticResolutionAudit(
            operator = query.operator,
            conflictGroupId = query.conflictGroupId,
            worldTime = query.worldTime,
            knowledgeTime = query.knowledgeTime,
            inputCount = inputCount,
            visibleVersionCount = visibleCount,
            temporalCandidateCount = temporal.size,
            selectedClaimIds = temporal.map { it.id }.distinct()
        )

    private val recordOrder =
        compareBy<SemanticClaimRecord>(
            { it.identity.subject.namespace },
            { it.identity.subject.id },
            { it.identity.predicate },
            { it.id.value },
            { it.version.value }
        )
}
