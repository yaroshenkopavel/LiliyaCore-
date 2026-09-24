package pro.liliya.core.semantic

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import pro.liliya.core.episodic.EpisodeId

class DeterministicSemanticClaimResolverContractTest {
    private val extraction = SemanticClaimExtractionProvenance(
        extractorId = "resolver-test",
        extractorVersion = "1.0.0",
        extractedAt = Instant.parse("2026-09-24T12:00:00Z")
    )

    private fun claim(
        objectText: String,
        version: Long = 1,
        observedAt: String,
        validFrom: String?,
        validUntil: String? = null,
        supersededAt: String? = null
    ): SemanticClaimRecord {
        val identity = SemanticClaimIdentity(
            subject = SemanticEntityReference("person", "user"),
            predicate = "preferred_language"
        )
        val value = SemanticClaimObject.Text(objectText)
        return SemanticClaimRecord(
            id = SemanticClaimIds.forClaim(identity, value),
            version = SemanticClaimVersion(version),
            identity = identity,
            objectValue = value,
            temporal = SemanticClaimTemporalState(
                observedAt = Instant.parse(observedAt),
                validFrom = validFrom?.let(Instant::parse),
                validUntil = validUntil?.let(Instant::parse),
                supersededAt = supersededAt?.let(Instant::parse)
            ),
            provenance = SemanticClaimProvenance(
                episodes = listOf(EpisodeId("episode-$objectText-$version")),
                extraction = extraction.copy(
                    extractedAt = Instant.parse(observedAt).plusSeconds(1)
                )
            )
        )
    }

    private fun query(
        operator: SemanticResolutionOperator,
        world: String,
        knowledge: String,
        cardinality: SemanticClaimCardinality = SemanticClaimCardinality.SINGLE
    ): SemanticResolutionQuery {
        val identity = SemanticClaimIdentity(
            subject = SemanticEntityReference("person", "user"),
            predicate = "preferred_language"
        )
        return SemanticResolutionQuery(
            conflictGroupId = SemanticClaimIds.forConflictGroup(identity),
            operator = operator,
            worldTime = Instant.parse(world),
            knowledgeTime = Instant.parse(knowledge),
            cardinality = cardinality
        )
    }

    @Test
    fun current_value_selects_claim_valid_at_world_and_known_at_knowledge_time() {
        val old = claim(
            objectText = "Russian",
            observedAt = "2026-01-02T00:00:00Z",
            validFrom = "2026-01-01T00:00:00Z",
            validUntil = "2026-09-01T00:00:00Z",
            supersededAt = "2026-09-02T00:00:00Z"
        )
        val current = claim(
            objectText = "Ukrainian",
            observedAt = "2026-09-02T00:00:00Z",
            validFrom = "2026-09-01T00:00:00Z"
        )

        val result = assertIs<SemanticResolutionResult.Resolved>(
            DeterministicSemanticClaimResolver.resolve(
                listOf(old, current),
                query(
                    SemanticResolutionOperator.CURRENT_VALUE,
                    world = "2026-09-24T00:00:00Z",
                    knowledge = "2026-09-24T00:00:00Z"
                )
            )
        )

        assertEquals(listOf(current.id), result.claims.map { it.id })
    }

    @Test
    fun historical_value_respects_past_world_and_past_knowledge_time() {
        val old = claim(
            objectText = "Russian",
            observedAt = "2026-01-02T00:00:00Z",
            validFrom = "2026-01-01T00:00:00Z",
            validUntil = "2026-09-01T00:00:00Z",
            supersededAt = "2026-09-02T00:00:00Z"
        )
        val current = claim(
            objectText = "Ukrainian",
            observedAt = "2026-09-02T00:00:00Z",
            validFrom = "2026-09-01T00:00:00Z"
        )

        val result = assertIs<SemanticResolutionResult.Resolved>(
            DeterministicSemanticClaimResolver.resolve(
                listOf(old, current),
                query(
                    SemanticResolutionOperator.HISTORICAL_VALUE,
                    world = "2026-08-15T00:00:00Z",
                    knowledge = "2026-08-20T00:00:00Z"
                )
            )
        )

        assertEquals(listOf(old.id), result.claims.map { it.id })
    }

    @Test
    fun previous_value_selects_latest_ended_validity_boundary() {
        val first = claim(
            objectText = "English",
            observedAt = "2025-01-02T00:00:00Z",
            validFrom = "2025-01-01T00:00:00Z",
            validUntil = "2025-12-01T00:00:00Z"
        )
        val previous = claim(
            objectText = "Russian",
            observedAt = "2025-12-02T00:00:00Z",
            validFrom = "2025-12-01T00:00:00Z",
            validUntil = "2026-09-01T00:00:00Z"
        )
        val current = claim(
            objectText = "Ukrainian",
            observedAt = "2026-09-02T00:00:00Z",
            validFrom = "2026-09-01T00:00:00Z"
        )

        val result = assertIs<SemanticResolutionResult.Resolved>(
            DeterministicSemanticClaimResolver.resolve(
                listOf(first, previous, current),
                query(
                    SemanticResolutionOperator.PREVIOUS_VALUE,
                    world = "2026-09-24T00:00:00Z",
                    knowledge = "2026-09-24T00:00:00Z"
                )
            )
        )

        assertEquals(listOf(previous.id), result.claims.map { it.id })
    }

    @Test
    fun single_cardinality_preserves_explicit_conflict() {
        val one = claim(
            objectText = "Russian",
            observedAt = "2026-09-01T00:00:00Z",
            validFrom = "2026-09-01T00:00:00Z"
        )
        val two = claim(
            objectText = "Ukrainian",
            observedAt = "2026-09-02T00:00:00Z",
            validFrom = "2026-09-01T00:00:00Z"
        )

        val result = assertIs<SemanticResolutionResult.Conflicted>(
            DeterministicSemanticClaimResolver.resolve(
                listOf(one, two),
                query(
                    SemanticResolutionOperator.CURRENT_VALUE,
                    world = "2026-09-24T00:00:00Z",
                    knowledge = "2026-09-24T00:00:00Z",
                    cardinality = SemanticClaimCardinality.SINGLE
                )
            )
        )

        assertEquals(SemanticConflictReason.MULTIPLE_ACTIVE_VALUES, result.reason)
        assertEquals(setOf(one.id, two.id), result.candidates.map { it.id }.toSet())
    }

    @Test
    fun multi_cardinality_returns_multiple_active_values_without_truth_promotion() {
        val one = claim(
            objectText = "Kotlin",
            observedAt = "2026-09-01T00:00:00Z",
            validFrom = "2026-09-01T00:00:00Z"
        ).copy(
            identity = SemanticClaimIdentity(
                SemanticEntityReference("person", "user"),
                "has_skill"
            ),
            objectValue = SemanticClaimObject.Text("Kotlin")
        ).let {
            it.copy(id = SemanticClaimIds.forClaim(it.identity, it.objectValue))
        }
        val two = one.copy(
            objectValue = SemanticClaimObject.Text("Python"),
            provenance = one.provenance.copy(episodes = listOf(EpisodeId("episode-python")))
        ).let {
            it.copy(id = SemanticClaimIds.forClaim(it.identity, it.objectValue))
        }
        val q = SemanticResolutionQuery(
            conflictGroupId = SemanticClaimIds.forConflictGroup(one.identity),
            operator = SemanticResolutionOperator.CURRENT_VALUE,
            worldTime = Instant.parse("2026-09-24T00:00:00Z"),
            knowledgeTime = Instant.parse("2026-09-24T00:00:00Z"),
            cardinality = SemanticClaimCardinality.MULTI
        )

        val result = assertIs<SemanticResolutionResult.Resolved>(
            DeterministicSemanticClaimResolver.resolve(listOf(one, two), q)
        )

        assertEquals(setOf(one.id, two.id), result.claims.map { it.id }.toSet())
    }

    @Test
    fun future_observation_is_not_visible_at_past_knowledge_time() {
        val future = claim(
            objectText = "Ukrainian",
            observedAt = "2026-09-20T00:00:00Z",
            validFrom = "2026-09-01T00:00:00Z"
        )

        assertIs<SemanticResolutionResult.Missing>(
            DeterministicSemanticClaimResolver.resolve(
                listOf(future),
                query(
                    SemanticResolutionOperator.HISTORICAL_VALUE,
                    world = "2026-09-10T00:00:00Z",
                    knowledge = "2026-09-10T00:00:00Z"
                )
            )
        )
    }

    @Test
    fun ambiguous_same_version_fails_closed_as_conflict() {
        val base = claim(
            objectText = "Russian",
            version = 2,
            observedAt = "2026-09-01T00:00:00Z",
            validFrom = "2026-09-01T00:00:00Z"
        )
        val divergent = base.copy(
            provenance = base.provenance.copy(
                episodes = listOf(EpisodeId("different-provenance"))
            )
        )

        val result = assertIs<SemanticResolutionResult.Conflicted>(
            DeterministicSemanticClaimResolver.resolve(
                listOf(base, divergent),
                query(
                    SemanticResolutionOperator.CURRENT_VALUE,
                    world = "2026-09-24T00:00:00Z",
                    knowledge = "2026-09-24T00:00:00Z"
                )
            )
        )

        assertEquals(SemanticConflictReason.AMBIGUOUS_SAME_VERSION, result.reason)
    }
}
