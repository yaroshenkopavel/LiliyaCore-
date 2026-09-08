package pro.liliya.android.runtime

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import pro.liliya.core.cognitive.CognitiveFinalizationResult
import pro.liliya.core.cognitive.CognitiveGovernedLearningFailure
import pro.liliya.core.cognitive.CognitiveGovernedLearningResult
import pro.liliya.core.cognitive.CognitiveGovernedLearningTerminalStatus
import pro.liliya.core.cognitive.CognitiveLearningReference
import pro.liliya.core.cognitive.CognitiveReflectionReference
import pro.liliya.core.cognitive.CognitiveResult
import pro.liliya.core.cognitive.CognitiveTurnGeneration
import pro.liliya.core.cognitive.CognitiveTurnId
import pro.liliya.core.cognitive.CognitiveTurnReference
import pro.liliya.core.cognitive.DecisionReference
import pro.liliya.core.cognitive.PlanningReference
import pro.liliya.core.cognitive.ReasoningReference
import pro.liliya.core.decision.DecisionGeneration
import pro.liliya.core.decision.DecisionId
import pro.liliya.core.learning.LearningApplicationDownstreamReference
import pro.liliya.core.learning.LearningApplicationGeneration
import pro.liliya.core.learning.LearningApplicationId
import pro.liliya.core.learning.LearningApplicationIntentReference
import pro.liliya.core.learning.LearningApplicationMutationApplicationReceipt
import pro.liliya.core.learning.LearningApplicationMutationGeneration
import pro.liliya.core.learning.LearningApplicationMutationId
import pro.liliya.core.learning.LearningApplicationMutationReference
import pro.liliya.core.learning.LearningApplicationTarget
import pro.liliya.core.learning.LearningCandidateId
import pro.liliya.core.learning.LearningDecisionGeneration
import pro.liliya.core.learning.LearningDecisionId
import pro.liliya.core.learning.LearningDecisionReference
import pro.liliya.core.learning.LearningGeneration
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.planning.PlanningGeneration
import pro.liliya.core.planning.PlanningProposalId
import pro.liliya.core.reasoning.ReasoningArtifactId
import pro.liliya.core.reasoning.ReasoningGeneration
import pro.liliya.core.reflection.ReflectionGeneration
import pro.liliya.core.reflection.ReflectionRecordId

class ProductLearningFollowUpHostContractTest {

    @Test
    fun successful_product_turn_converts_only_exact_learning_reference() {
        val completed = completedTurn(
            candidateId = "PRIVATE-CANDIDATE-ID",
            generation = 7
        )

        val reference = completed.learningFollowUpReference()

        assertEquals("PRIVATE-CANDIDATE-ID", reference.cognitive.id.value)
        assertEquals(7L, reference.cognitive.generation.value)
        assertEquals(7L, reference.generation)
        assertFalse("PRIVATE-CANDIDATE-ID" in reference.toString())
        assertFalse("PRIVATE-REPLY" in reference.toString())
        assertTrue("generation=7" in reference.toString())
    }

    @Test
    fun applied_synchronized_maps_to_product_applied_synchronized() {
        val host = host(
            processed(
                governed = applied(),
                semantic = AndroidHeartSemanticLearningSyncStatus.SYNCHRONIZED
            )
        )

        val result = assertIs<ProductLearningFollowUpResult.Applied>(
            host.process(reference())
        )

        assertEquals(ProductLearningSemanticStatus.SYNCHRONIZED, result.semantic)
    }

    @Test
    fun applied_rebuild_required_remains_applied_and_surfaces_recovery_required() {
        val host = host(
            processed(
                governed = applied(),
                semantic = AndroidHeartSemanticLearningSyncStatus.REBUILD_REQUIRED
            )
        )

        val result = assertIs<ProductLearningFollowUpResult.Applied>(
            host.process(reference())
        )

        assertEquals(ProductLearningSemanticStatus.RECOVERY_REQUIRED, result.semantic)
    }

    @Test
    fun applied_with_not_applicable_semantic_state_fails_closed() {
        val host = host(
            processed(
                governed = applied(),
                semantic = AndroidHeartSemanticLearningSyncStatus.NOT_APPLICABLE
            )
        )

        assertEquals(
            ProductLearningFollowUpResult.InternalFailure,
            host.process(reference())
        )
    }

    @Test
    fun governance_rejected_maps_without_exposing_decision_details() {
        val host = host(
            processed(
                governed = CognitiveGovernedLearningResult.GovernanceRejected(
                    decision = decision()
                )
            )
        )

        assertEquals(
            ProductLearningFollowUpResult.GovernanceRejected,
            host.process(reference())
        )
    }

    @Test
    fun every_terminal_status_maps_exactly() {
        CognitiveGovernedLearningTerminalStatus.entries.forEach { status ->
            val host = host(
                processed(
                    governed = CognitiveGovernedLearningResult.AlreadyProcessed(status)
                )
            )

            val result = assertIs<ProductLearningFollowUpResult.AlreadyProcessed>(
                host.process(reference())
            )

            assertEquals(ProductLearningTerminalStatus.valueOf(status.name), result.status)
        }
    }

    @Test
    fun every_core_rejection_maps_to_bounded_product_rejection() {
        CognitiveGovernedLearningFailure.entries.forEach { reason ->
            val host = host(
                processed(
                    governed = CognitiveGovernedLearningResult.Rejected(reason)
                )
            )

            val result = assertIs<ProductLearningFollowUpResult.Rejected>(
                host.process(reference())
            )

            assertEquals(ProductLearningRejection.valueOf(reason.name), result.reason)
        }
    }

    @Test
    fun completion_compensated_and_partial_failure_remain_distinct() {
        val compensated = host(
            processed(
                governed = CognitiveGovernedLearningResult.CompletionCompensated(
                    decision = decision(),
                    application = application(),
                    mutation = mutation(),
                    target = LearningApplicationTarget.MEMORY
                )
            )
        )
        val partial = host(
            processed(
                governed = CognitiveGovernedLearningResult.PartialFailure(
                    decision = decision(),
                    application = application(),
                    mutation = mutation(),
                    downstream = LearningApplicationDownstreamReference.Memory(
                        recordId = MemoryRecordId("memory-1"),
                        generation = MemoryGeneration(1)
                    )
                )
            )
        )

        assertEquals(
            ProductLearningFollowUpResult.CompletionCompensated,
            compensated.process(reference())
        )
        assertEquals(
            ProductLearningFollowUpResult.PartialFailure,
            partial.process(reference())
        )
    }

    @Test
    fun production_not_ready_and_failed_map_fail_closed() {
        assertEquals(
            ProductLearningFollowUpResult.NotReady,
            host(AndroidHeartProductionGovernedLearningProcessResult.NotReady)
                .process(reference())
        )
        assertEquals(
            ProductLearningFollowUpResult.InternalFailure,
            host(AndroidHeartProductionGovernedLearningProcessResult.Failed)
                .process(reference())
        )
    }

    @Test
    fun downstream_exception_is_contained_without_retry() {
        var calls = 0
        val host = ProductLearningFollowUpHost(
            ProductLearningFollowUpPort {
                calls += 1
                error("PRIVATE-DOWNSTREAM-EXCEPTION")
            }
        )

        assertEquals(
            ProductLearningFollowUpResult.InternalFailure,
            host.process(reference())
        )
        assertEquals(1, calls)
    }

    @Test
    fun one_product_process_delegates_exact_reference_once() {
        var calls = 0
        var seen: CognitiveLearningReference? = null
        val host = ProductLearningFollowUpHost(
            ProductLearningFollowUpPort { actual ->
                calls += 1
                seen = actual
                AndroidHeartProductionGovernedLearningProcessResult.NotReady
            }
        )
        val reference = ProductLearningFollowUpReference(
            CognitiveLearningReference(
                LearningCandidateId("exact-candidate"),
                LearningGeneration(9)
            )
        )

        host.process(reference)

        assertEquals(1, calls)
        assertEquals("exact-candidate", seen?.id?.value)
        assertEquals(9L, seen?.generation?.value)
    }

    @Test
    fun host_adds_no_terminal_registry_or_retry_semantics() {
        var calls = 0
        val host = ProductLearningFollowUpHost(
            ProductLearningFollowUpPort {
                calls += 1
                processed(
                    governed = CognitiveGovernedLearningResult.AlreadyProcessed(
                        CognitiveGovernedLearningTerminalStatus.APPLIED
                    )
                )
            }
        )

        repeat(2) {
            assertIs<ProductLearningFollowUpResult.AlreadyProcessed>(
                host.process(reference())
            )
        }

        assertEquals(2, calls)
    }

    @Test
    fun basic_product_chat_result_still_exposes_no_learning_reference() {
        val methods = ProductChatResult.Completed::class.java.methods
            .map { it.name.lowercase() }
            .toSet()

        assertFalse(methods.any { "learning" in it })
        assertFalse(methods.any { "candidate" in it })
        assertTrue(methods.any { "reply" in it })
    }

    @Test
    fun product_rendering_exposes_no_private_learning_content() {
        val ref = ProductLearningFollowUpReference(
            CognitiveLearningReference(
                LearningCandidateId("PRIVATE-CANDIDATE"),
                LearningGeneration(3)
            )
        )
        val host = host(AndroidHeartProductionGovernedLearningProcessResult.Failed)

        assertFalse("PRIVATE-CANDIDATE" in ref.toString())
        assertFalse("PRIVATE" in host.toString())
    }

    private fun host(
        result: AndroidHeartProductionGovernedLearningProcessResult
    ): ProductLearningFollowUpHost =
        ProductLearningFollowUpHost(
            ProductLearningFollowUpPort { result }
        )

    private fun processed(
        governed: CognitiveGovernedLearningResult,
        semantic: AndroidHeartSemanticLearningSyncStatus =
            AndroidHeartSemanticLearningSyncStatus.NOT_APPLICABLE
    ): AndroidHeartProductionGovernedLearningProcessResult.Processed =
        AndroidHeartProductionGovernedLearningProcessResult.Processed(
            AndroidHeartGovernedLearningResult(
                governed = governed,
                semanticSync = semantic
            )
        )

    private fun reference(): ProductLearningFollowUpReference =
        ProductLearningFollowUpReference(
            CognitiveLearningReference(
                LearningCandidateId("candidate-1"),
                LearningGeneration(1)
            )
        )

    private fun applied(): CognitiveGovernedLearningResult.Applied {
        val mutation = mutation()
        return CognitiveGovernedLearningResult.Applied(
            decision = decision(),
            application = application(),
            mutation = mutation,
            receipt = LearningApplicationMutationApplicationReceipt(
                mutation = mutation,
                target = LearningApplicationTarget.MEMORY,
                downstream = LearningApplicationDownstreamReference.Memory(
                    recordId = MemoryRecordId("memory-applied"),
                    generation = MemoryGeneration(1)
                )
            )
        )
    }

    private fun decision(): LearningDecisionReference =
        LearningDecisionReference(
            LearningDecisionId("decision-1"),
            LearningDecisionGeneration(1)
        )

    private fun application(): LearningApplicationIntentReference =
        LearningApplicationIntentReference(
            LearningApplicationId("application-1"),
            LearningApplicationGeneration(1)
        )

    private fun mutation(): LearningApplicationMutationReference =
        LearningApplicationMutationReference(
            LearningApplicationMutationId("mutation-1"),
            LearningApplicationMutationGeneration(1)
        )

    private fun completedTurn(
        candidateId: String,
        generation: Long
    ): ProductTurnResult.Completed {
        val turn = CognitiveTurnReference(
            CognitiveTurnId("product-turn"),
            CognitiveTurnGeneration(1)
        )
        val planning = PlanningReference(
            PlanningProposalId("planning"),
            PlanningGeneration(1)
        )
        val reasoning = ReasoningReference(
            ReasoningArtifactId("reasoning"),
            ReasoningGeneration(1)
        )
        val decision = DecisionReference(
            DecisionId("product-decision"),
            DecisionGeneration(1)
        )
        return ProductTurnResult.Completed(
            turn = turn,
            finalization = CognitiveFinalizationResult.Completed(
                result = CognitiveResult(
                    turn = turn,
                    planning = planning,
                    reasoning = reasoning,
                    decision = decision,
                    content = "PRIVATE-REPLY",
                    createdAt = Instant.parse("2026-09-08T00:00:00Z")
                ),
                reflection = CognitiveReflectionReference(
                    ReflectionRecordId("reflection"),
                    ReflectionGeneration(1)
                ),
                learning = CognitiveLearningReference(
                    LearningCandidateId(candidateId),
                    LearningGeneration(generation)
                )
            ),
            streamedChunkCount = 0,
            streamedCharacterCount = 0
        )
    }
}
