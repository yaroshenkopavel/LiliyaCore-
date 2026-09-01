package pro.liliya.core.cognitive

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.decision.DecisionComposition
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.learning.LearningCandidate
import pro.liliya.core.learning.LearningCandidateId
import pro.liliya.core.learning.LearningComposition
import pro.liliya.core.learning.LearningInstallResult
import pro.liliya.core.learning.LearningOrigin
import pro.liliya.core.learning.LearningSourceId
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.planning.PlanningComposition
import pro.liliya.core.reasoning.ReasoningComposition
import pro.liliya.core.reflection.ReflectionComposition
import pro.liliya.core.reflection.ReflectionInstallResult
import pro.liliya.core.reflection.ReflectionOrigin
import pro.liliya.core.reflection.ReflectionRecord
import pro.liliya.core.reflection.ReflectionRecordId
import pro.liliya.core.reflection.ReflectionSourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CognitiveFinalizationCoordinatorContractTest {
    private data class Fixture(
        val scope: CognitiveRuntimeScopeId,
        val logs: InMemoryLogWriter,
        val planning: PlanningComposition,
        val reasoning: ReasoningComposition,
        val decision: DecisionComposition,
        val reflection: ReflectionComposition,
        val learning: LearningComposition,
        val composition: CognitiveRuntimeComposition
    )

    private fun fixture(
        outcome: CognitiveOutcomeMaterializationPort,
        inferenceOutput: String = "private inference output",
        limits: CognitiveRuntimeLimits = limits()
    ): Fixture {
        val logs = InMemoryLogWriter()
        val correlation = AtomicInteger(0)
        val clock = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "slice4-${correlation.incrementAndGet()}" }
        )
        val planning = PlanningComposition(foundation)
        val reasoning = ReasoningComposition(foundation)
        val decision = DecisionComposition(foundation)
        val reflection = ReflectionComposition(foundation)
        val learning = LearningComposition(foundation)
        val scope = CognitiveRuntimeScopeId("slice4-test-scope")
        val perKind = mutableMapOf<CognitiveArtifactIdKind, Int>()
        val ids = CognitiveArtifactIdSource { kind ->
            val next = (perKind[kind] ?: 0) + 1
            perKind[kind] = next
            "${kind.name.lowercase().replace('_', '-')}-$next"
        }
        val composition = CognitiveRuntimeComposition(
            foundation = foundation,
            scope = scope,
            memoryRetrieval = MemoryRetrievalPort { MemoryRetrievalResult(emptyList()) },
            knowledgeRetrieval = KnowledgeRetrievalPort { KnowledgeRetrievalResult(emptyList()) },
            selfSnapshots = SelfSnapshotPort { null },
            personalitySnapshots = PersonalitySnapshotPort { emptyList() },
            inference = CognitiveInferencePort { request ->
                CognitiveInferenceResult.Succeeded(request.turn, inferenceOutput)
            },
            limits = limits,
            materialization = CognitiveMaterializationPort {
                CognitiveMaterializationResult.Succeeded(generationCandidate())
            },
            planning = planning,
            reasoning = reasoning,
            decision = decision,
            artifactIds = ids,
            timestamps = CognitiveTimestampSource {
                Instant.parse("2026-09-01T17:40:00Z").plusSeconds(clock.incrementAndGet().toLong())
            },
            outcomeMaterialization = outcome,
            reflection = reflection,
            learning = learning
        )
        return Fixture(scope, logs, planning, reasoning, decision, reflection, learning, composition)
    }

    @Test
    fun successful_finalization_installs_exact_reflection_and_learning_then_completes_turn() {
        val f = fixture(
            outcome = CognitiveOutcomeMaterializationPort {
                CognitiveOutcomeMaterializationResult.Succeeded(outcomeCandidate())
            }
        )
        val turn = readyTurn(f.composition)
        val generation = assertIs<CognitiveGenerationResult.Succeeded>(
            f.composition.generateCognition(turn.reference)
        )

        val completed = assertIs<CognitiveFinalizationResult.Completed>(
            f.composition.finalizeCognition(turn.reference)
        )

        assertEquals(CognitiveTurnLifecycle.COMPLETED, turn.lifecycle())
        assertNull(f.composition.currentReference())
        assertEquals(turn.reference, completed.result.turn)
        assertEquals(generation.planning, completed.result.planning)
        assertEquals(generation.reasoning, completed.result.reasoning)
        assertEquals(generation.decision, completed.result.decision)
        assertEquals("private caller result", completed.result.content)

        val reflectionSnapshot = f.reflection.inspect(completed.reflection.id)!!
        assertEquals(completed.reflection.generation, reflectionSnapshot.generation)
        val reflectionOrigin = assertIs<ReflectionOrigin.Declared>(reflectionSnapshot.record.origin)
        assertEquals(COGNITIVE_RUNTIME_RESULT_SOURCE_ID, reflectionOrigin.sourceId.value)
        assertEquals(
            CognitiveProvenance.resultToken(f.scope, turn.reference, generation.decision).value,
            reflectionOrigin.sourceReference?.value
        )

        val learningSnapshot = f.learning.inspect(completed.learning.id)!!
        assertEquals(completed.learning.generation, learningSnapshot.generation)
        val learningOrigin = assertIs<LearningOrigin.Reflection>(learningSnapshot.candidate.origin)
        assertEquals(reflectionSnapshot.record.id, learningOrigin.recordId)
        assertEquals(reflectionSnapshot.generation, learningOrigin.generation)
        assertEquals("private learning proposal", learningSnapshot.candidate.proposal)
    }

    @Test
    fun finalization_observability_never_contains_raw_turn_or_private_outcome_payloads() {
        val rawTurnId = "raw-turn-id-never-log-slice4"
        val privateInput = "private-input-never-log-slice4"
        val privateInference = "private-inference-never-log-slice4"
        val privateResult = "private-result-never-log-slice4"
        val privateReflection = "private-reflection-never-log-slice4"
        val privateLearning = "private-learning-never-log-slice4"
        val f = fixture(
            inferenceOutput = privateInference,
            outcome = CognitiveOutcomeMaterializationPort {
                CognitiveOutcomeMaterializationResult.Succeeded(
                    CognitiveOutcomeCandidate(privateResult, privateReflection, privateLearning)
                )
            }
        )
        val turn = readyTurn(f.composition, rawTurnId, privateInput)
        assertIs<CognitiveGenerationResult.Succeeded>(f.composition.generateCognition(turn.reference))
        assertIs<CognitiveFinalizationResult.Completed>(f.composition.finalizeCognition(turn.reference))

        val forbidden = listOf(
            rawTurnId,
            privateInput,
            privateInference,
            privateResult,
            privateReflection,
            privateLearning
        )
        f.logs.snapshot().forEach { event ->
            forbidden.forEach { secret ->
                assertFalse(event.message.contains(secret))
                assertFalse(event.metadata.values.any { it.contains(secret) })
            }
            assertFalse(event.metadata.keys.any { it == "cognitiveTurnId" })
            assertFalse(event.metadata.keys.any { it.contains("authority", ignoreCase = true) })
            assertFalse(event.metadata.keys.any { it.contains("license", ignoreCase = true) })
        }
    }

    @Test
    fun reentrant_finalization_is_rejected_without_disrupting_outer_attempt() {
        lateinit var f: Fixture
        var nested: CognitiveFinalizationResult? = null
        f = fixture(
            outcome = CognitiveOutcomeMaterializationPort { request ->
                nested = f.composition.finalizeCognition(request.turn)
                CognitiveOutcomeMaterializationResult.Succeeded(outcomeCandidate())
            }
        )
        val turn = readyTurn(f.composition)
        assertIs<CognitiveGenerationResult.Succeeded>(f.composition.generateCognition(turn.reference))

        assertIs<CognitiveFinalizationResult.Completed>(f.composition.finalizeCognition(turn.reference))
        val nestedRejected = assertIs<CognitiveFinalizationResult.Rejected>(nested)
        assertEquals(CognitiveFinalizationFailure.FINALIZATION_IN_PROGRESS, nestedRejected.reason)
        assertEquals(CognitiveTurnLifecycle.COMPLETED, turn.lifecycle())
    }

    @Test
    fun preexisting_reflection_id_collision_fails_closed_without_adopting_or_removing_old_record() {
        val f = fixture(
            outcome = CognitiveOutcomeMaterializationPort {
                CognitiveOutcomeMaterializationResult.Succeeded(outcomeCandidate())
            }
        )
        val turn = readyTurn(f.composition)
        assertIs<CognitiveGenerationResult.Succeeded>(f.composition.generateCognition(turn.reference))
        val preexisting = ReflectionRecord(
            id = ReflectionRecordId("reflection-record-1"),
            origin = ReflectionOrigin.Declared(ReflectionSourceId("preexisting")),
            content = "preexisting private reflection",
            createdAt = Instant.parse("2026-09-01T17:00:00Z")
        )
        assertIs<ReflectionInstallResult.Installed>(f.reflection.install(preexisting))

        val rejected = assertIs<CognitiveFinalizationResult.Rejected>(
            f.composition.finalizeCognition(turn.reference)
        )

        assertEquals(CognitiveFinalizationFailure.ARTIFACT_ID_COLLISION, rejected.reason)
        assertEquals(listOf(preexisting), f.reflection.snapshot())
        assertTrue(f.learning.snapshot().isEmpty())
        assertEquals(CognitiveTurnLifecycle.FAILED, turn.lifecycle())
        assertNull(f.composition.currentReference())
    }

    @Test
    fun preexisting_learning_id_collision_fails_closed_without_installing_reflection_or_touching_old_candidate() {
        val f = fixture(
            outcome = CognitiveOutcomeMaterializationPort {
                CognitiveOutcomeMaterializationResult.Succeeded(outcomeCandidate())
            }
        )
        val turn = readyTurn(f.composition)
        assertIs<CognitiveGenerationResult.Succeeded>(f.composition.generateCognition(turn.reference))
        val preexisting = LearningCandidate(
            id = LearningCandidateId("learning-candidate-1"),
            origin = LearningOrigin.Declared(LearningSourceId("preexisting")),
            proposal = "preexisting private learning",
            createdAt = Instant.parse("2026-09-01T17:00:00Z")
        )
        assertIs<LearningInstallResult.Installed>(f.learning.install(preexisting))

        val rejected = assertIs<CognitiveFinalizationResult.Rejected>(
            f.composition.finalizeCognition(turn.reference)
        )

        assertEquals(CognitiveFinalizationFailure.ARTIFACT_ID_COLLISION, rejected.reason)
        assertTrue(f.reflection.snapshot().isEmpty())
        assertEquals(listOf(preexisting), f.learning.snapshot())
        assertEquals(CognitiveTurnLifecycle.FAILED, turn.lifecycle())
        assertNull(f.composition.currentReference())
    }

    @Test
    fun over_bound_outcome_fails_before_reflection_or_learning_installation() {
        val strict = limits(maxResultChars = 8)
        val f = fixture(
            limits = strict,
            outcome = CognitiveOutcomeMaterializationPort {
                CognitiveOutcomeMaterializationResult.Succeeded(
                    CognitiveOutcomeCandidate(
                        resultContent = "x".repeat(9),
                        reflectionContent = "bounded",
                        learningProposal = "bounded"
                    )
                )
            }
        )
        val turn = readyTurn(f.composition)
        assertIs<CognitiveGenerationResult.Succeeded>(f.composition.generateCognition(turn.reference))

        val rejected = assertIs<CognitiveFinalizationResult.Rejected>(
            f.composition.finalizeCognition(turn.reference)
        )

        assertEquals(CognitiveFinalizationFailure.OUTCOME_LIMIT_REJECTED, rejected.reason)
        assertTrue(f.reflection.snapshot().isEmpty())
        assertTrue(f.learning.snapshot().isEmpty())
        assertEquals(CognitiveTurnLifecycle.FAILED, turn.lifecycle())
    }

    private fun readyTurn(
        composition: CognitiveRuntimeComposition,
        id: String = "slice4-turn",
        input: String = "private user input"
    ): CognitiveTurnHandle {
        val turn = assertIs<CognitiveTurnRegistrationResult.Registered>(
            composition.beginTurn(CognitiveTurnId(id), CognitiveInput(input))
        ).turn
        assertIs<CognitiveContextAssemblyResult.Published>(composition.assembleContext(turn.reference))
        return turn
    }

    private fun generationCandidate() = CognitiveMaterializationCandidate(
        planningGoal = "private planning goal",
        planningSteps = listOf("private planning step"),
        reasoningPremises = listOf("private reasoning premise"),
        reasoningAnalysis = "private reasoning analysis",
        reasoningConclusion = "private reasoning conclusion",
        decisionOptions = listOf("private option a", "private option b"),
        selectedDecisionOptionIndex = 1,
        decisionRationale = "private decision rationale"
    )

    private fun outcomeCandidate() = CognitiveOutcomeCandidate(
        resultContent = "private caller result",
        reflectionContent = "private reflection content",
        learningProposal = "private learning proposal"
    )

    private fun limits(maxResultChars: Int = 128) = CognitiveRuntimeLimits(
        maxRuntimeScopeIdChars = 64,
        maxTurnIdChars = 128,
        maxInputChars = 128,
        maxContextItems = 8,
        maxContextItemChars = 128,
        maxRetrievalResults = 4,
        maxInferenceOutputChars = 128,
        maxPlanningGoalChars = 128,
        maxPlanningSteps = 4,
        maxPlanningStepChars = 128,
        maxReasoningPremises = 4,
        maxReasoningPremiseChars = 128,
        maxReasoningAnalysisChars = 128,
        maxReasoningConclusionChars = 128,
        maxDecisionOptions = 4,
        maxDecisionOptionChars = 128,
        maxDecisionRationaleChars = 128,
        maxResultChars = maxResultChars,
        maxReflectionChars = 128,
        maxLearningProposalChars = 128,
        maxProvenanceReferenceChars = 128
    )
}
