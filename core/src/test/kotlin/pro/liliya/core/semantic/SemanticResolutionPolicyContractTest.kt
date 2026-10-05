package pro.liliya.core.semantic

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import pro.liliya.core.episodic.EpisodeId

class SemanticResolutionPolicyContractTest {
    private fun claim(
        predicate: String,
        value: String,
        observedAt: String
    ): SemanticClaimRecord {
        val identity = SemanticClaimIdentity(
            subject = SemanticEntityReference("person", "user"),
            predicate = predicate
        )
        val objectValue = SemanticClaimObject.Text(value)
        val observed = Instant.parse(observedAt)
        return SemanticClaimRecord(
            id = SemanticClaimIds.forClaim(identity, objectValue),
            version = SemanticClaimVersion(1),
            identity = identity,
            objectValue = objectValue,
            temporal = SemanticClaimTemporalState(
                observedAt = observed,
                validFrom = Instant.parse("2026-09-01T00:00:00Z")
            ),
            provenance = SemanticClaimProvenance(
                episodes = listOf(EpisodeId("episode-$predicate-$value")),
                extraction = SemanticClaimExtractionProvenance(
                    extractorId = "semantic-policy-test",
                    extractorVersion = "1.0.0",
                    extractedAt = observed.plusSeconds(1)
                )
            )
        )
    }

    @Test
    fun aggregation_policy_returns_all_valid_values_and_records_policy_in_audit() {
        val kotlin = claim("has_skill", "Kotlin", "2026-09-20T00:00:00Z")
        val python = claim("has_skill", "Python", "2026-09-21T00:00:00Z")
        val policy = SemanticResolutionPolicy(
            id = "skills.aggregate-current.v1",
            operator = SemanticResolutionOperator.AGGREGATION,
            cardinality = SemanticClaimCardinality.MULTI
        )

        val result = assertIs<SemanticResolutionResult.Resolved>(
            DeterministicSemanticResolutionPolicyEngine.resolve(
                records = listOf(python, kotlin),
                conflictGroupId = SemanticClaimIds.forConflictGroup(kotlin.identity),
                policy = policy,
                worldTime = Instant.parse("2026-09-24T00:00:00Z"),
                knowledgeTime = Instant.parse("2026-09-24T00:00:00Z")
            )
        )

        assertEquals(listOf(kotlin.id, python.id).sortedBy { it.value }, result.claims.map { it.id })
        assertEquals(policy.id, result.audit.policyId)
        assertEquals(SemanticResolutionOperator.AGGREGATION, result.audit.operator)
    }

    @Test
    fun single_value_policy_preserves_conflict_instead_of_choosing_winner() {
        val russian = claim("preferred_language", "Russian", "2026-09-20T00:00:00Z")
        val ukrainian = claim("preferred_language", "Ukrainian", "2026-09-21T00:00:00Z")
        val policy = SemanticResolutionPolicy(
            id = "preferred-language.current-single.v1",
            operator = SemanticResolutionOperator.CURRENT_VALUE,
            cardinality = SemanticClaimCardinality.SINGLE
        )

        val result = assertIs<SemanticResolutionResult.Conflicted>(
            DeterministicSemanticResolutionPolicyEngine.resolve(
                records = listOf(ukrainian, russian),
                conflictGroupId = SemanticClaimIds.forConflictGroup(russian.identity),
                policy = policy,
                worldTime = Instant.parse("2026-09-24T00:00:00Z"),
                knowledgeTime = Instant.parse("2026-09-24T00:00:00Z")
            )
        )

        assertEquals(SemanticConflictReason.MULTIPLE_ACTIVE_VALUES, result.reason)
        assertEquals(policy.id, result.audit.policyId)
    }

    @Test
    fun aggregation_order_is_deterministic_independent_of_input_order() {
        val one = claim("has_skill", "Kotlin", "2026-09-20T00:00:00Z")
        val two = claim("has_skill", "Python", "2026-09-21T00:00:00Z")
        val policy = SemanticResolutionPolicy(
            id = "skills.aggregate-current.v1",
            operator = SemanticResolutionOperator.AGGREGATION,
            cardinality = SemanticClaimCardinality.MULTI
        )
        val group = SemanticClaimIds.forConflictGroup(one.identity)
        val world = Instant.parse("2026-09-24T00:00:00Z")

        val forward = assertIs<SemanticResolutionResult.Resolved>(
            DeterministicSemanticResolutionPolicyEngine.resolve(
                listOf(one, two), group, policy, world, world
            )
        )
        val reverse = assertIs<SemanticResolutionResult.Resolved>(
            DeterministicSemanticResolutionPolicyEngine.resolve(
                listOf(two, one), group, policy, world, world
            )
        )

        assertEquals(forward.claims, reverse.claims)
        assertEquals(forward.audit.selectedClaimIds, reverse.audit.selectedClaimIds)
    }
}
