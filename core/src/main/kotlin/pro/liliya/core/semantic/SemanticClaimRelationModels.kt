package pro.liliya.core.semantic

import java.time.Instant

enum class SemanticClaimRelationType {
    SUPERSEDES,
    CONTRADICTS
}

data class SemanticClaimVersionReference(
    val claimId: SemanticClaimId,
    val version: SemanticClaimVersion
)

data class SemanticClaimRelation(
    val type: SemanticClaimRelationType,
    val source: SemanticClaimVersionReference,
    val target: SemanticClaimVersionReference,
    val recordedAt: Instant
) {
    init {
        require(source != target) { "semantic claim relation must not self-reference exact version" }
    }
}
