package pro.liliya.android.semanticprovider

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.semantic.SemanticClaimId
import pro.liliya.core.semantic.SemanticClaimSourceCheckpoint
import pro.liliya.core.semantic.SemanticClaimVectorProviderResult
import pro.liliya.core.semantic.SemanticClaimVersion
import pro.liliya.core.semantic.SemanticClaimVersionReference

class OfflineSemanticClaimVectorDiscoveryAdapterContractTest {
    private val identity = OfflineSemanticClaimVectorIdentity(
        profileId = "semantic-e5-small-v1",
        profileGeneration = 2,
        indexGeneration = 9,
        source = SemanticClaimSourceCheckpoint(4, 7, 11)
    )

    @Test
    fun ranked_provider_candidates_map_without_exposing_provider_internals() {
        val a = reference("a", 1)
        val b = reference("b", 2)
        var requested = 0
        val adapter = OfflineSemanticClaimVectorDiscoveryAdapter(
            OfflineSemanticClaimVectorDiscoveryPort { _, maxCandidates ->
                requested = maxCandidates
                OfflineSemanticClaimVectorDiscoveryResult.Ranked(
                    identity = identity,
                    candidates = listOf(
                        OfflineSemanticClaimVectorCandidate(a, 0.8),
                        OfflineSemanticClaimVectorCandidate(b, -0.1)
                    ),
                    truncated = true
                )
            }
        )

        val result = assertIs<SemanticClaimVectorProviderResult.Ranked>(
            adapter.discover("query", 16)
        )
        assertEquals(16, requested)
        assertEquals("semantic-e5-small-v1", result.identity.profileId)
        assertEquals(2L, result.identity.profileGeneration)
        assertEquals(9L, result.identity.indexGeneration)
        assertEquals(identity.source, result.identity.source)
        assertEquals(listOf(a, b), result.candidates.map { it.reference })
        assertEquals(listOf(0.8, -0.1), result.candidates.map { it.similarity })
        assertTrue(result.truncated)
    }

    @Test
    fun unavailable_and_thrown_provider_failures_are_explicit() {
        val unavailable = OfflineSemanticClaimVectorDiscoveryAdapter(
            OfflineSemanticClaimVectorDiscoveryPort { _, _ ->
                OfflineSemanticClaimVectorDiscoveryResult.Unavailable(
                    SemanticProviderFailureKind.INDEX_UNAVAILABLE,
                    "claim projection unavailable"
                )
            }
        )
        val unavailableResult = assertIs<SemanticClaimVectorProviderResult.Unavailable>(
            unavailable.discover("query", 8)
        )
        assertTrue(unavailableResult.reason.contains("INDEX_UNAVAILABLE"))

        val throwing = OfflineSemanticClaimVectorDiscoveryAdapter(
            OfflineSemanticClaimVectorDiscoveryPort { _, _ ->
                throw IllegalStateException("boom")
            }
        )
        val failed = assertIs<SemanticClaimVectorProviderResult.Failed>(
            throwing.discover("query", 8)
        )
        assertTrue(failed.reason.contains("provider failed"))
        assertIs<IllegalStateException>(failed.throwable)
    }

    @Test
    fun over_budget_and_duplicate_provider_candidates_fail_closed() {
        val overBudget = OfflineSemanticClaimVectorDiscoveryAdapter(
            OfflineSemanticClaimVectorDiscoveryPort { _, _ ->
                OfflineSemanticClaimVectorDiscoveryResult.Ranked(
                    identity,
                    listOf(
                        OfflineSemanticClaimVectorCandidate(reference("a", 1), 0.9),
                        OfflineSemanticClaimVectorCandidate(reference("b", 1), 0.8)
                    )
                )
            }
        )
        val over = assertIs<SemanticClaimVectorProviderResult.Failed>(
            overBudget.discover("query", 1)
        )
        assertTrue(over.reason.contains("budget"))

        val same = reference("same", 1)
        val duplicates = OfflineSemanticClaimVectorDiscoveryAdapter(
            OfflineSemanticClaimVectorDiscoveryPort { _, _ ->
                OfflineSemanticClaimVectorDiscoveryResult.Ranked(
                    identity,
                    listOf(
                        OfflineSemanticClaimVectorCandidate(same, 0.9),
                        OfflineSemanticClaimVectorCandidate(same, 0.7)
                    )
                )
            }
        )
        val duplicate = assertIs<SemanticClaimVectorProviderResult.Failed>(
            duplicates.discover("query", 8)
        )
        assertTrue(duplicate.reason.contains("duplicate"))
    }

    private fun reference(id: String, version: Long) =
        SemanticClaimVersionReference(
            SemanticClaimId("claim-" + id),
            SemanticClaimVersion(version)
        )
}