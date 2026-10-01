package pro.liliya.core.asf

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentWorkerSpecializationContractTest {
    private val now = Instant.parse("2026-10-01T12:00:00Z")

    private val micro = profile(
        workerClass = AgentWorkerClass.MICRO,
        name = "micro-specialist",
        runtimeId = "micro-runtime"
    )
    private val full = profile(
        workerClass = AgentWorkerClass.FULL,
        name = "full-specialist",
        runtimeId = "full-runtime"
    )

    @Test
    fun cold_start_is_explicit_and_does_not_invent_a_preference() {
        val decision = AgentWorkerSpecializationAdvisor.advise(
            taskClass = AgentWorkerRequirement.NARROW_MULTI_STEP,
            eligibleProfiles = listOf(micro, full),
            evidence = AgentWorkerSpecializationEvidenceSet.empty(),
            now = now
        )

        val noRecommendation =
            assertIs<AgentWorkerSpecializationDecision.NoRecommendation>(decision)
        assertEquals(
            AgentWorkerSpecializationNoRecommendationReason.COLD_START,
            noRecommendation.reason
        )
        assertTrue(
            noRecommendation.signals.all {
                it.state == AgentWorkerSpecializationSignalState.COLD_START
            }
        )
    }

    @Test
    fun controlled_baseline_evidence_can_advisably_prefer_a_better_profile() {
        val records = buildList {
            repeat(3) { index ->
                add(
                    evidence(
                        profile = full,
                        reference = "eval-full-$index",
                        observedAt = now.minusSeconds((index + 1L) * 3_600),
                        comparison = comparison(
                            completion = AgentBlueprintEvaluationRelation.BETTER,
                            inference = AgentBlueprintEvaluationRelation.BETTER,
                            context = AgentBlueprintEvaluationRelation.BETTER
                        )
                    )
                )
                add(
                    evidence(
                        profile = micro,
                        reference = "eval-micro-$index",
                        observedAt = now.minusSeconds((index + 1L) * 3_600),
                        comparison = comparison()
                    )
                )
            }
        }

        val decision = AgentWorkerSpecializationAdvisor.advise(
            taskClass = AgentWorkerRequirement.NARROW_MULTI_STEP,
            eligibleProfiles = listOf(micro, full),
            evidence = AgentWorkerSpecializationEvidenceSet.create(records),
            now = now
        )

        val recommended = assertIs<AgentWorkerSpecializationDecision.Recommended>(decision)
        assertEquals(full.canonicalKey, recommended.profile.canonicalKey)
        assertTrue(recommended.signal.weightedBaselineDelta > 0)
        assertEquals(3, recommended.signal.evidenceCount)
        assertTrue(recommended.signal.advisoryOnly)
    }

    @Test
    fun sparse_evidence_fails_closed_even_when_all_observations_are_positive() {
        val records = listOf(
            evidence(full, "sparse-1", now.minusSeconds(60), positiveComparison()),
            evidence(full, "sparse-2", now.minusSeconds(120), positiveComparison())
        )

        val decision = AgentWorkerSpecializationAdvisor.advise(
            taskClass = AgentWorkerRequirement.NARROW_MULTI_STEP,
            eligibleProfiles = listOf(full),
            evidence = AgentWorkerSpecializationEvidenceSet.create(records),
            now = now
        )

        val noRecommendation =
            assertIs<AgentWorkerSpecializationDecision.NoRecommendation>(decision)
        assertEquals(
            AgentWorkerSpecializationNoRecommendationReason.INSUFFICIENT_EVIDENCE,
            noRecommendation.reason
        )
        assertEquals(
            AgentWorkerSpecializationSignalState.INSUFFICIENT_EVIDENCE,
            noRecommendation.signals.single().state
        )
    }

    @Test
    fun stale_success_history_does_not_become_a_current_specialization_preference() {
        val staleAt = now.minusSeconds(45L * 24 * 60 * 60)
        val records = (1..3).map {
            evidence(full, "stale-$it", staleAt.minusSeconds(it.toLong()), positiveComparison())
        }

        val decision = AgentWorkerSpecializationAdvisor.advise(
            taskClass = AgentWorkerRequirement.NARROW_MULTI_STEP,
            eligibleProfiles = listOf(full),
            evidence = AgentWorkerSpecializationEvidenceSet.create(records),
            now = now
        )

        val noRecommendation =
            assertIs<AgentWorkerSpecializationDecision.NoRecommendation>(decision)
        assertEquals(
            AgentWorkerSpecializationNoRecommendationReason.STALE_EVIDENCE,
            noRecommendation.reason
        )
        assertEquals(0, noRecommendation.signals.single().evidenceCount)
    }

    @Test
    fun statistics_cannot_override_hard_worker_compatibility() {
        val records = (1..4).map {
            evidence(
                profile = micro,
                reference = "micro-broad-$it",
                observedAt = now.minusSeconds(it * 60L),
                comparison = positiveComparison()
            )
        }

        val decision = AgentWorkerSpecializationAdvisor.advise(
            taskClass = AgentWorkerRequirement.BROAD_SPECIALIST,
            eligibleProfiles = listOf(micro, full),
            evidence = AgentWorkerSpecializationEvidenceSet.create(records),
            now = now
        )

        val noRecommendation =
            assertIs<AgentWorkerSpecializationDecision.NoRecommendation>(decision)
        assertEquals(
            AgentWorkerSpecializationNoRecommendationReason.COLD_START,
            noRecommendation.reason
        )
        assertEquals(listOf(full.canonicalKey), noRecommendation.signals.map { it.profile.canonicalKey })
    }

    @Test
    fun duplicate_evaluation_evidence_cannot_self_reinforce_a_profile() {
        val one = evidence(
            full,
            "same-evaluation",
            now.minusSeconds(60),
            positiveComparison()
        )
        val duplicate = evidence(
            full,
            "same-evaluation",
            now.minusSeconds(30),
            positiveComparison()
        )

        assertFailsWith<IllegalArgumentException> {
            AgentWorkerSpecializationEvidenceSet.create(listOf(one, duplicate))
        }
    }

    @Test
    fun future_dated_evidence_fails_closed_instead_of_increasing_recency() {
        val records = (1..3).map {
            evidence(
                full,
                "future-$it",
                now.plusSeconds(it * 60L),
                positiveComparison()
            )
        }

        val decision = AgentWorkerSpecializationAdvisor.advise(
            taskClass = AgentWorkerRequirement.NARROW_MULTI_STEP,
            eligibleProfiles = listOf(full),
            evidence = AgentWorkerSpecializationEvidenceSet.create(records),
            now = now
        )

        val noRecommendation =
            assertIs<AgentWorkerSpecializationDecision.NoRecommendation>(decision)
        assertEquals(
            AgentWorkerSpecializationNoRecommendationReason.INVALID_FUTURE_EVIDENCE,
            noRecommendation.reason
        )
        assertTrue(noRecommendation.signals.isEmpty())
    }

    @Test
    fun non_positive_baseline_history_never_forces_a_specialization_recommendation() {
        val records = (1..3).map {
            evidence(
                full,
                "worse-$it",
                now.minusSeconds(it * 60L),
                comparison(
                    completion = AgentBlueprintEvaluationRelation.WORSE,
                    inference = AgentBlueprintEvaluationRelation.WORSE
                )
            )
        }

        val decision = AgentWorkerSpecializationAdvisor.advise(
            taskClass = AgentWorkerRequirement.NARROW_MULTI_STEP,
            eligibleProfiles = listOf(full),
            evidence = AgentWorkerSpecializationEvidenceSet.create(records),
            now = now
        )

        val noRecommendation =
            assertIs<AgentWorkerSpecializationDecision.NoRecommendation>(decision)
        assertEquals(
            AgentWorkerSpecializationNoRecommendationReason.NO_POSITIVE_BASELINE_EVIDENCE,
            noRecommendation.reason
        )
        assertTrue(noRecommendation.signals.single().weightedBaselineDelta < 0)
    }

    @Test
    fun recency_decay_is_bounded_and_old_non_stale_evidence_has_less_weight() {
        val policy = AgentWorkerSpecializationPolicy(
            minimumEvidenceCount = 3,
            freshWindow = java.time.Duration.ofDays(7),
            staleAfter = java.time.Duration.ofDays(30),
            freshWeight = 4,
            agingWeight = 2,
            oldWeight = 1
        )
        val records = listOf(
            evidence(full, "fresh", now.minusSeconds(60), positiveComparison()),
            evidence(full, "aging", now.minusSeconds(10L * 24 * 60 * 60), positiveComparison()),
            evidence(full, "old", now.minusSeconds(25L * 24 * 60 * 60), positiveComparison())
        )

        val decision = AgentWorkerSpecializationAdvisor.advise(
            taskClass = AgentWorkerRequirement.NARROW_MULTI_STEP,
            eligibleProfiles = listOf(full),
            evidence = AgentWorkerSpecializationEvidenceSet.create(records),
            now = now,
            policy = policy
        )

        val signal = assertIs<AgentWorkerSpecializationDecision.Recommended>(decision).signal
        assertEquals(7, signal.weightedBaselineDelta)
        assertEquals(3, signal.evidenceCount)
    }

    @Test
    fun specialization_contracts_do_not_carry_truth_authority_permission_or_execution_grants() {
        val forbidden = listOf(
            "truth", "authority", "permission", "executiongrant",
            "credential", "secret", "token", "license", "principal"
        )
        listOf(
            AgentWorkerSpecializationEvidence::class.java,
            AgentWorkerSpecializationSignal::class.java,
            AgentWorkerSpecializationProfile::class.java
        ).forEach { type ->
            val names = type.declaredFields.map { it.name.lowercase() }
            forbidden.forEach { word ->
                assertTrue(names.none { word in it })
            }
        }
    }

    private fun profile(
        workerClass: AgentWorkerClass,
        name: String,
        runtimeId: String
    ): AgentWorkerSpecializationProfile {
        val blueprint = AgentBlueprint.create(
            AgentBlueprintVersion(1),
            name,
            "bounded-specialization",
            AgentCognitiveScope.create(listOf("verification"))
        )
        return AgentWorkerSpecializationProfile(
            workerClass = workerClass,
            blueprint = AgentBlueprintReference(blueprint.id, blueprint.version),
            runtime = AgentWorkerRuntimeDescriptor(
                runtimeId = runtimeId,
                kind = AgentWorkerRuntimeKind.DETERMINISTIC
            )
        )
    }

    private fun evidence(
        profile: AgentWorkerSpecializationProfile,
        reference: String,
        observedAt: Instant,
        comparison: AgentBlueprintEvaluationComparison
    ) = AgentWorkerSpecializationEvidence.fromCoreEvaluation(
        taskClass = AgentWorkerRequirement.NARROW_MULTI_STEP,
        profile = profile,
        evaluationReference = reference,
        comparisonToBaseline = comparison,
        observedAt = observedAt,
        provenanceReferences = listOf(
            "asf-evaluation:$reference",
            "asf-root:root-asf-j"
        )
    )

    private fun positiveComparison() = comparison(
        completion = AgentBlueprintEvaluationRelation.BETTER
    )

    private fun comparison(
        completion: AgentBlueprintEvaluationRelation = AgentBlueprintEvaluationRelation.EQUAL,
        challenged: AgentBlueprintEvaluationRelation = AgentBlueprintEvaluationRelation.EQUAL,
        unresolved: AgentBlueprintEvaluationRelation = AgentBlueprintEvaluationRelation.EQUAL,
        wallClock: AgentBlueprintEvaluationRelation = AgentBlueprintEvaluationRelation.EQUAL,
        inference: AgentBlueprintEvaluationRelation = AgentBlueprintEvaluationRelation.EQUAL,
        context: AgentBlueprintEvaluationRelation = AgentBlueprintEvaluationRelation.EQUAL,
        artifacts: AgentBlueprintEvaluationRelation = AgentBlueprintEvaluationRelation.EQUAL,
        retries: AgentBlueprintEvaluationRelation = AgentBlueprintEvaluationRelation.EQUAL,
        cancellations: AgentBlueprintEvaluationRelation = AgentBlueprintEvaluationRelation.EQUAL
    ) = AgentBlueprintEvaluationComparison(
        completion = completion,
        challengedFindings = challenged,
        unresolvedConflicts = unresolved,
        wallClock = wallClock,
        inference = inference,
        context = context,
        artifacts = artifacts,
        retries = retries,
        cancellations = cancellations
    )
}
