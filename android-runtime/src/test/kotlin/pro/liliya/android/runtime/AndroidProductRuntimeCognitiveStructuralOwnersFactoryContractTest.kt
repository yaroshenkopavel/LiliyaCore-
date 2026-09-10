package pro.liliya.android.runtime

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import pro.liliya.core.cognitive.CognitiveArtifactIdKind
import pro.liliya.core.cognitive.CognitiveRuntimeLimits
import pro.liliya.core.cognitive.StructuredCognitiveMaterializationPort
import pro.liliya.core.cognitive.StructuredCognitiveOutcomeMaterializationPort

class AndroidProductRuntimeCognitiveStructuralOwnersFactoryContractTest {
    @Test
    fun production_structural_owners_reuse_frozen_structured_materializers() {
        val result = AndroidProductRuntimeCognitiveStructuralOwnersFactory.create(
            CognitiveRuntimeLimits()
        )

        val ready =
            assertIs<AndroidProductRuntimeCognitiveStructuralOwnersResult.Ready>(result)
        assertIs<StructuredCognitiveMaterializationPort>(
            ready.owners.cognitiveMaterialization
        )
        assertIs<StructuredCognitiveOutcomeMaterializationPort>(
            ready.owners.outcomeMaterialization
        )
    }

    @Test
    fun generated_ids_are_bounded_structural_and_do_not_embed_content() {
        val tokens = ArrayDeque(
            listOf(
                "0123456789abcdef0123456789abcdef",
                "fedcba9876543210fedcba9876543210"
            )
        )
        val result = AndroidProductRuntimeCognitiveStructuralOwnersFactory.create(
            limits = CognitiveRuntimeLimits(maxGeneratedArtifactIdChars = 64),
            clock = Clock.fixed(
                Instant.parse("2026-09-10T00:00:00Z"),
                ZoneOffset.UTC
            ),
            tokenSource = { tokens.removeFirst() }
        )

        val ready =
            assertIs<AndroidProductRuntimeCognitiveStructuralOwnersResult.Ready>(result)
        val first = ready.owners.artifactIds.next(CognitiveArtifactIdKind.PLANNING_PROPOSAL)
        val second = ready.owners.artifactIds.next(CognitiveArtifactIdKind.MEMORY_RECORD)

        assertEquals(
            "planning-proposal-0123456789abcdef0123456789abcdef",
            first
        )
        assertEquals(
            "memory-record-fedcba9876543210fedcba9876543210",
            second
        )
        assertTrue(first.length <= 64)
        assertTrue(second.length <= 64)
    }

    @Test
    fun timestamp_source_uses_exact_utc_instant_from_clock() {
        val expected = Instant.parse("2026-09-10T00:00:00Z")
        val result = AndroidProductRuntimeCognitiveStructuralOwnersFactory.create(
            limits = CognitiveRuntimeLimits(),
            clock = Clock.fixed(expected, ZoneOffset.UTC),
            tokenSource = { "0123456789abcdef0123456789abcdef" }
        )

        val ready =
            assertIs<AndroidProductRuntimeCognitiveStructuralOwnersResult.Ready>(result)
        assertEquals(expected, ready.owners.timestamps.now())
    }

    @Test
    fun impossible_artifact_id_bound_is_rejected_at_factory_time() {
        val result = AndroidProductRuntimeCognitiveStructuralOwnersFactory.create(
            limits = CognitiveRuntimeLimits(maxGeneratedArtifactIdChars = 8),
            clock = Clock.systemUTC(),
            tokenSource = { "0123456789abcdef0123456789abcdef" }
        )

        assertIs<AndroidProductRuntimeCognitiveStructuralOwnersResult.Rejected>(result)
    }
}
