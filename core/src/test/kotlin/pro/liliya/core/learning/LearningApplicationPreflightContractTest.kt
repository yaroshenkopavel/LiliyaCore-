package pro.liliya.core.learning

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LearningApplicationPreflightContractTest {
    private data class Fixture(
        val candidates: LearningComposition,
        val decisions: LearningDecisionComposition,
        val policies: LearningPolicyComposition,
        val applications: LearningApplicationComposition,
        val validator: LearningApplicationPreflightValidator
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "preflight-${sequence.incrementAndGet()}" }
        )
        val candidates = LearningComposition(foundation)
        val decisions = LearningDecisionComposition(foundation)
        val policies = LearningPolicyComposition(foundation)
        val applications = LearningApplicationComposition(foundation)
        return Fixture(
            candidates = candidates,
            decisions = decisions,
            policies = policies,
            applications = applications,
            validator = LearningApplicationPreflightValidator(
                applications = applications,
                decisions = decisions,
                candidates = candidates,
                policies = policies
            )
        )
    }

    private fun installCandidate(f: Fixture): LearningOwnership =
        assertIs<LearningInstallResult.Installed>(
            f.candidates.install(
                LearningCandidate(
                    id = LearningCandidateId("candidate-1"),
                    origin = LearningOrigin.Declared(LearningSourceId("test")),
                    proposal = "sensitive proposal",
                    createdAt = Instant.parse("2026-08-29T00:00:00Z")
                )
            )
        ).ownership

    private fun installDecision(
        f: Fixture,
        candidate: LearningOwnership,
        disposition: LearningDecisionDisposition = LearningDecisionDisposition.APPROVE
    ): LearningDecisionOwnership = assertIs<LearningDecisionInstallResult.Installed>(
        f.decisions.install(
            LearningDecision(
                id = LearningDecisionId("decision-1"),
                candidate = LearningCandidateReference(candidate.candidate.id, candidate.generation),
                disposition = disposition,
                rationale = "sensitive rationale",
                createdAt = Instant.parse("2026-08-29T00:01:00Z")
            )
        )
    ).ownership

    private fun installPolicy(f: Fixture): LearningPolicyOwnership =
        assertIs<LearningPolicyInstallResult.Installed>(
            f.policies.install(
                LearningPolicy(
                    id = LearningPolicyId("policy-1"),
                    rule = "sensitive rule",
                    createdAt = Instant.parse("2026-08-29T00:02:00Z")
                )
            )
        ).ownership

    private fun installApplication(
        f: Fixture,
        decision: LearningDecisionOwnership,
        policy: LearningPolicyOwnership
    ): LearningApplicationOwnership = assertIs<LearningApplicationInstallResult.Installed>(
        f.applications.install(
            LearningApplicationIntent(
                id = LearningApplicationId("application-1"),
                decision = LearningDecisionReference(decision.decision.id, decision.generation),
                policy = LearningPolicyReference(policy.policy.id, policy.generation),
                target = LearningApplicationTarget.MEMORY,
                createdAt = Instant.parse("2026-08-29T00:03:00Z")
            )
        )
    ).ownership

    @Test
    fun exact_current_chain_with_approve_is_ready_for_authorization_only() {
        val f = fixture()
        val candidate = installCandidate(f)
        val decision = installDecision(f, candidate)
        val policy = installPolicy(f)
        val application = installApplication(f, decision, policy)

        val result = assertIs<LearningApplicationPreflightResult.ReadyForAuthorization>(
            f.validator.validate(
                LearningApplicationIntentReference(application.intent.id, application.generation)
            )
        )

        assertEquals(application.intent.decision, result.receipt.decision)
        assertEquals(decision.decision.candidate, result.receipt.candidate)
        assertEquals(application.intent.policy, result.receipt.policy)
        assertEquals(LearningApplicationTarget.MEMORY, result.receipt.target)
    }

    @Test
    fun rejected_decision_never_reaches_ready_for_authorization() {
        val f = fixture()
        val candidate = installCandidate(f)
        val decision = installDecision(f, candidate, LearningDecisionDisposition.REJECT)
        val policy = installPolicy(f)
        val application = installApplication(f, decision, policy)

        val result = assertIs<LearningApplicationPreflightResult.Rejected>(
            f.validator.validate(
                LearningApplicationIntentReference(application.intent.id, application.generation)
            )
        )

        assertEquals(LearningApplicationPreflightRejection.DECISION_NOT_APPROVED, result.reason)
    }

    @Test
    fun stale_application_generation_is_rejected() {
        val f = fixture()
        val candidate = installCandidate(f)
        val decision = installDecision(f, candidate)
        val policy = installPolicy(f)
        val application = installApplication(f, decision, policy)

        val result = assertIs<LearningApplicationPreflightResult.Rejected>(
            f.validator.validate(
                LearningApplicationIntentReference(
                    application.intent.id,
                    LearningApplicationGeneration(application.generation.value + 1L)
                )
            )
        )

        assertEquals(LearningApplicationPreflightRejection.APPLICATION_GENERATION_MISMATCH, result.reason)
    }

    @Test
    fun missing_exact_policy_is_rejected_without_policy_evaluation() {
        val f = fixture()
        val candidate = installCandidate(f)
        val decision = installDecision(f, candidate)
        val missingPolicy = object : LearningPolicyOwnership {
            override val policy = LearningPolicy(
                LearningPolicyId("missing-policy"),
                "never evaluated",
                Instant.parse("2026-08-29T00:02:00Z")
            )
            override val generation = LearningPolicyGeneration(99L)
            override fun remove(): Boolean = false
        }
        val application = installApplication(f, decision, missingPolicy)

        val result = assertIs<LearningApplicationPreflightResult.Rejected>(
            f.validator.validate(
                LearningApplicationIntentReference(application.intent.id, application.generation)
            )
        )

        assertEquals(LearningApplicationPreflightRejection.POLICY_MISSING, result.reason)
    }
}
