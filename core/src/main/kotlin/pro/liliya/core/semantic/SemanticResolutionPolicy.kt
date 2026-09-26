package pro.liliya.core.semantic

import java.time.Instant

data class SemanticResolutionPolicy(
    val id: String,
    val operator: SemanticResolutionOperator,
    val cardinality: SemanticClaimCardinality
) {
    init {
        require(id.isNotBlank()) { "semantic resolution policy id must not be blank" }
    }
}

object DeterministicSemanticResolutionPolicyEngine {
    fun resolve(
        records: List<SemanticClaimRecord>,
        conflictGroupId: SemanticClaimConflictGroupId,
        policy: SemanticResolutionPolicy,
        worldTime: Instant,
        knowledgeTime: Instant
    ): SemanticResolutionResult =
        DeterministicSemanticClaimResolver.resolve(
            records = records,
            query = SemanticResolutionQuery(
                conflictGroupId = conflictGroupId,
                operator = policy.operator,
                worldTime = worldTime,
                knowledgeTime = knowledgeTime,
                cardinality = policy.cardinality,
                policyId = policy.id
            )
        )
}
