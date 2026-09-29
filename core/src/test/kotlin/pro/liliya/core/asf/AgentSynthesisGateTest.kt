package pro.liliya.core.asf

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentSynthesisGateTest {
    private val now = Instant.parse("2026-09-29T14:30:00Z")
    private val root = AgentRootTaskId("root-d")

    @Test
    fun support_and_challenge_remain_unresolved_regardless_of_majority() {
        val gate = AgentSynthesisGate()
        val claim = AgentClaimKey("claim:a")

        val contributions = listOf(
            contribution("a1", AgentReviewKind.VERIFICATION, claim, AgentFindingDisposition.SUPPORTS, "evidence:a"),
            contribution("a2", AgentReviewKind.VERIFICATION, claim, AgentFindingDisposition.SUPPORTS, "evidence:b"),
            contribution("a3", AgentReviewKind.CRITIQUE, claim, AgentFindingDisposition.SUPPORTS, "evidence:c"),
            contribution("a4", AgentReviewKind.ADVERSARIAL_REVIEW, claim, AgentFindingDisposition.CHALLENGES, "evidence:d")
        )

        val result = gate.synthesize(contributions)

        assertEquals(root, result.rootTaskId)
        assertEquals(1, result.findings.size)
        assertEquals(AgentSynthesisFindingState.UNRESOLVED_CONFLICT, result.findings.single().state)
        assertEquals(4, result.findings.single().contributorArtifactIds.size)
        assertEquals(
            listOf("evidence:a", "evidence:b", "evidence:c", "evidence:d"),
            result.findings.single().evidenceReferences
        )
    }

    @Test
    fun unanimous_structured_support_is_supported_but_not_truth_selection() {
        val result = AgentSynthesisGate().synthesize(
            listOf(
                contribution("s1", AgentReviewKind.VERIFICATION, AgentClaimKey("claim:a"), AgentFindingDisposition.SUPPORTS, "evidence:a"),
                contribution("s2", AgentReviewKind.CRITIQUE, AgentClaimKey("claim:a"), AgentFindingDisposition.SUPPORTS, "evidence:b")
            )
        )

        assertEquals(AgentSynthesisFindingState.SUPPORTED, result.findings.single().state)
        assertEquals(2, result.contributingArtifactIds.size)
    }

    @Test
    fun support_plus_inconclusive_remains_inconclusive() {
        val claim = AgentClaimKey("claim:a")
        val result = AgentSynthesisGate().synthesize(
            listOf(
                contribution("i1", AgentReviewKind.VERIFICATION, claim, AgentFindingDisposition.SUPPORTS, "evidence:a"),
                contribution("i2", AgentReviewKind.CRITIQUE, claim, AgentFindingDisposition.INCONCLUSIVE, "evidence:b")
            )
        )

        assertEquals(AgentSynthesisFindingState.INCONCLUSIVE, result.findings.single().state)
    }

    @Test
    fun challenge_only_is_challenged() {
        val result = AgentSynthesisGate().synthesize(
            listOf(
                contribution(
                    "c1",
                    AgentReviewKind.ADVERSARIAL_REVIEW,
                    AgentClaimKey("claim:a"),
                    AgentFindingDisposition.CHALLENGES,
                    "evidence:a"
                )
            )
        )

        assertEquals(AgentSynthesisFindingState.CHALLENGED, result.findings.single().state)
    }

    @Test
    fun mixed_root_tasks_fail_closed() {
        val first = contribution(
            "r1", AgentReviewKind.VERIFICATION, AgentClaimKey("claim:a"),
            AgentFindingDisposition.SUPPORTS, "evidence:a"
        )
        val secondArtifact = artifact(
            producer = "r2",
            rootTaskId = AgentRootTaskId("other-root"),
            provenance = listOf("evidence:b")
        )
        val second = AgentReviewContribution.create(
            AgentReviewKind.CRITIQUE,
            secondArtifact,
            listOf(
                AgentReviewFinding.create(
                    AgentClaimKey("claim:a"),
                    AgentFindingDisposition.CHALLENGES,
                    listOf("evidence:b")
                )
            )
        )

        assertFailsWith<IllegalArgumentException> {
            AgentSynthesisGate().synthesize(listOf(first, second))
        }
    }

    @Test
    fun finding_cannot_claim_evidence_outside_source_artifact_provenance() {
        val source = artifact("p1", root, listOf("evidence:a"))

        assertFailsWith<IllegalArgumentException> {
            AgentReviewContribution.create(
                AgentReviewKind.VERIFICATION,
                source,
                listOf(
                    AgentReviewFinding.create(
                        AgentClaimKey("claim:a"),
                        AgentFindingDisposition.SUPPORTS,
                        listOf("evidence:not-present")
                    )
                )
            )
        }
    }

    @Test
    fun duplicate_artifact_contribution_fails_closed() {
        val one = contribution(
            "d1", AgentReviewKind.VERIFICATION, AgentClaimKey("claim:a"),
            AgentFindingDisposition.SUPPORTS, "evidence:a"
        )

        assertFailsWith<IllegalArgumentException> {
            AgentSynthesisGate().synthesize(listOf(one, one))
        }
    }

    @Test
    fun one_contribution_cannot_submit_multiple_findings_for_same_claim() {
        val source = artifact("dup-claim", root, listOf("evidence:a", "evidence:b"))
        val claim = AgentClaimKey("claim:a")

        assertFailsWith<IllegalArgumentException> {
            AgentReviewContribution.create(
                AgentReviewKind.CRITIQUE,
                source,
                listOf(
                    AgentReviewFinding.create(claim, AgentFindingDisposition.SUPPORTS, listOf("evidence:a")),
                    AgentReviewFinding.create(claim, AgentFindingDisposition.CHALLENGES, listOf("evidence:b"))
                )
            )
        }
    }

    @Test
    fun synthesis_preserves_claim_order_and_contributor_provenance() {
        val result = AgentSynthesisGate().synthesize(
            listOf(
                contribution(
                    "z",
                    AgentReviewKind.VERIFICATION,
                    AgentClaimKey("claim:z"),
                    AgentFindingDisposition.SUPPORTS,
                    "evidence:z"
                ),
                contribution(
                    "a",
                    AgentReviewKind.CRITIQUE,
                    AgentClaimKey("claim:a"),
                    AgentFindingDisposition.CHALLENGES,
                    "evidence:a"
                )
            )
        )

        assertEquals(listOf("claim:a", "claim:z"), result.findings.map { it.claimKey.value })
        assertEquals(2, result.contributingArtifactIds.size)
        assertTrue(result.findings.all { it.contributorArtifactIds.isNotEmpty() })
    }

    @Test
    fun synthesis_contracts_contain_no_authority_execution_or_secret_fields() {
        val forbidden = listOf(
            "authority", "permission", "credential", "secret",
            "token", "license", "executiongrant", "principal"
        )
        listOf(
            AgentReviewFinding::class.java,
            AgentReviewContribution::class.java,
            AgentSynthesisFinding::class.java,
            AgentSynthesisResult::class.java
        ).forEach { type ->
            val fields = type.declaredFields.map { it.name.lowercase() }
            forbidden.forEach { word ->
                assertTrue(fields.none { word in it }, "${type.simpleName} contains forbidden field: $word")
            }
        }
    }

    private fun contribution(
        producer: String,
        kind: AgentReviewKind,
        claimKey: AgentClaimKey,
        disposition: AgentFindingDisposition,
        evidence: String
    ): AgentReviewContribution {
        val artifact = artifact(producer, root, listOf(evidence))
        return AgentReviewContribution.create(
            kind,
            artifact,
            listOf(AgentReviewFinding.create(claimKey, disposition, listOf(evidence)))
        )
    }

    private fun artifact(
        producer: String,
        rootTaskId: AgentRootTaskId,
        provenance: List<String>
    ): AgentArtifact = AgentArtifact.create(
        producerId = AgentInstanceId("producer-$producer"),
        producerGeneration = AgentInstanceGeneration(1),
        rootTaskId = rootTaskId,
        kind = "review",
        payloadDigest = "sha256:$producer",
        provenanceReferences = provenance,
        createdAt = now
    )
}
