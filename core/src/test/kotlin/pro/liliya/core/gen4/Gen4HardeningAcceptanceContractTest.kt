package pro.liliya.core.gen4

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import pro.liliya.core.autonomy.AutonomyExecutionCheckpoint
import pro.liliya.core.closedloop.ClosedLoopActionAttemptReference
import pro.liliya.core.closedloop.ClosedLoopBudget
import pro.liliya.core.closedloop.ClosedLoopDefinition
import pro.liliya.core.closedloop.ClosedLoopIteration
import pro.liliya.core.closedloop.ClosedLoopRecord
import pro.liliya.core.evaluation.OutcomeEvaluationRecord
import pro.liliya.core.evaluation.OutcomeEvaluationRepository
import pro.liliya.core.reflection.BoundedReflectionRecord
import pro.liliya.core.reflection.BoundedReflectionRepository
import pro.liliya.core.recovery.RecoveryDecision
import pro.liliya.core.recovery.RecoveryRequest
import pro.liliya.core.reliability.ReliabilityAssessment
import pro.liliya.core.reliability.ReliabilityAssessmentRequest
import pro.liliya.core.retrieval.AssociativeRetrievalPolicy
import pro.liliya.core.semantic.EncryptedPersistentKnowledgeGraphProjectionStore
import pro.liliya.core.semantic.EncryptedPersistentSemanticClaimRepository
import pro.liliya.core.semantic.KnowledgeGraphFragment
import pro.liliya.core.semantic.SemanticClaimRecord
import pro.liliya.core.strategy.StrategyAdaptationRepository
import pro.liliya.core.strategy.StrategyCandidate

class Gen4HardeningAcceptanceContractTest {
    @Test
    fun derived_q_to_x_models_do_not_persist_permission_bearing_state() {
        val modelTypes = listOf(
            SemanticClaimRecord::class.java,
            KnowledgeGraphFragment::class.java,
            AssociativeRetrievalPolicy::class.java,
            OutcomeEvaluationRecord::class.java,
            BoundedReflectionRecord::class.java,
            StrategyCandidate::class.java,
            ReliabilityAssessmentRequest::class.java,
            ReliabilityAssessment::class.java,
            ClosedLoopDefinition::class.java,
            ClosedLoopActionAttemptReference::class.java,
            ClosedLoopIteration::class.java,
            ClosedLoopRecord::class.java
        )

        val forbidden = setOf(
            "authoritygrant",
            "authoritytoken",
            "capabilitygrant",
            "capabilitytoken",
            "executiongrant",
            "permission",
            "licensegrant",
            "bearertoken"
        )

        val fields = modelTypes.flatMap { type ->
            type.declaredFields.map { field ->
                "${type.simpleName}.${field.name}".lowercase()
            }
        }

        forbidden.forEach { marker ->
            assertTrue(
                fields.none { marker in it },
                "Gen4 derived state must not persist permission-bearing field marker '$marker': $fields"
            )
        }
    }

    @Test
    fun recovery_and_durable_checkpoint_state_do_not_store_grants_or_tokens() {
        val modelTypes = listOf(
            RecoveryRequest::class.java,
            RecoveryDecision.Selected::class.java,
            RecoveryDecision.Rejected::class.java,
            AutonomyExecutionCheckpoint::class.java
        )
        val fields = modelTypes.flatMap { type ->
            type.declaredFields.map { field -> field.name.lowercase() }
        }

        listOf(
            "authoritygrant",
            "authoritytoken",
            "capabilitygrant",
            "capabilitytoken",
            "executiongrant",
            "permission",
            "bearertoken"
        ).forEach { marker ->
            assertTrue(fields.none { marker in it })
        }
    }

    @Test
    fun durable_and_cognitive_repository_surfaces_do_not_expose_lifetime_load_all_api() {
        val surfaces = listOf(
            EncryptedPersistentSemanticClaimRepository::class.java,
            EncryptedPersistentKnowledgeGraphProjectionStore::class.java,
            OutcomeEvaluationRepository::class.java,
            BoundedReflectionRepository::class.java,
            StrategyAdaptationRepository::class.java
        )

        val forbiddenMethodNames = setOf(
            "loadall",
            "readall",
            "fetchall",
            "allrecords",
            "lifetime",
            "fullsnapshot",
            "loadsnapshot"
        )

        surfaces.forEach { type ->
            val methods = type.methods.map { it.name.lowercase() }.toSet()
            forbiddenMethodNames.forEach { forbidden ->
                assertFalse(
                    forbidden in methods,
                    "${type.simpleName} must not expose lifetime API '$forbidden': $methods"
                )
            }
        }

        val semanticMethods =
            EncryptedPersistentSemanticClaimRepository::class.java.methods.map { it.name }.toSet()
        assertTrue("readExact" in semanticMethods)
        assertTrue("claimPage" in semanticMethods)

        val graphMethods =
            EncryptedPersistentKnowledgeGraphProjectionStore::class.java.methods.map { it.name }.toSet()
        assertTrue(graphMethods.any { it.startsWith("readFragment") })
        assertTrue("readManifest" in graphMethods)

        val outcomeMethods = OutcomeEvaluationRepository::class.java.methods.map { it.name }.toSet()
        val reflectionMethods = BoundedReflectionRepository::class.java.methods.map { it.name }.toSet()
        val strategyMethods = StrategyAdaptationRepository::class.java.methods.map { it.name }.toSet()
        listOf(outcomeMethods, reflectionMethods, strategyMethods).forEach { methods ->
            assertTrue(methods.any { it.startsWith("lookup") })
            assertTrue("page" in methods)
        }
    }

    @Test
    fun q_to_x_hard_bounds_remain_finite_and_small_enough_for_bounded_operation() {
        assertTrue(AssociativeRetrievalPolicy.MAX_PARTICIPANTS in 1..64)
        assertTrue(ClosedLoopBudget.MAX_ITERATIONS in 1..128)
        assertTrue(ClosedLoopBudget.MAX_EVIDENCE_PER_ITERATION in 1..1024)
        assertTrue(ClosedLoopBudget.MAX_TOTAL_EVIDENCE in 1..16384)
        assertTrue(ClosedLoopBudget.MAX_ELAPSED_SECONDS in 1..(7L * 24L * 60L * 60L))
    }

    @Test
    fun stage_x_records_keep_action_provenance_but_not_authority_material() {
        val actionFields =
            ClosedLoopActionAttemptReference::class.java.declaredFields.map { it.name.lowercase() }

        assertTrue("actionid" in actionFields)
        assertTrue("decisionid" in actionFields)
        assertTrue("orchestrationintentid" in actionFields)

        listOf(
            "principal",
            "authority",
            "token",
            "permission",
            "grant"
        ).forEach { marker ->
            assertTrue(actionFields.none { marker in it })
        }
    }
}
