package pro.liliya.core.learning

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.CapabilityAuthorityComposition
import pro.liliya.core.authority.CapabilityOwnershipResult
import pro.liliya.core.authority.DirectAuthorityGrant
import pro.liliya.core.authority.DirectAuthorityGrantOwnershipResult
import pro.liliya.core.capability.CapabilityDescriptor
import pro.liliya.core.capability.CapabilityProviderId
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.memory.MemoryProvenance
import pro.liliya.core.memory.MemoryRecord
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemorySourceId
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LearningApplicationMutationAuthorizationGateContractTest {
    private data class Fixture(
        val candidates: LearningComposition,
        val decisions: LearningDecisionComposition,
        val policies: LearningPolicyComposition,
        val applications: LearningApplicationComposition,
        val mutations: LearningApplicationMutationComposition,
        val authority: CapabilityAuthorityComposition,
        val gate: LearningApplicationMutationAuthorizationGate
    )

    private data class InstalledApplication(
        val ownership: LearningApplicationOwnership,
        val target: LearningApplicationTarget
    )

    private val principal = AuthorityPrincipal("learning-controller")

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "mutation-gate-${sequence.incrementAndGet()}" }
        )
        val candidates = LearningComposition(foundation)
        val decisions = LearningDecisionComposition(foundation)
        val policies = LearningPolicyComposition(foundation)
        val applications = LearningApplicationComposition(foundation)
        val mutations = LearningApplicationMutationComposition(foundation)
        val authority = CapabilityAuthorityComposition(foundation)
        val preflight = LearningApplicationPreflightValidator(
            applications = applications,
            decisions = decisions,
            candidates = candidates,
            policies = policies
        )
        val authorizer = LearningApplicationAuthorizer(preflight, authority)
        return Fixture(
            candidates = candidates,
            decisions = decisions,
            policies = policies,
            applications = applications,
            mutations = mutations,
            authority = authority,
            gate = LearningApplicationMutationAuthorizationGate(mutations, authorizer)
        )
    }

    private fun installApplication(
        f: Fixture,
        target: LearningApplicationTarget = LearningApplicationTarget.MEMORY
    ): InstalledApplication {
        val candidate = assertIs<LearningInstallResult.Installed>(
            f.candidates.install(
                LearningCandidate(
                    id = LearningCandidateId("candidate-1"),
                    origin = LearningOrigin.Declared(LearningSourceId("test")),
                    proposal = "proposal",
                    createdAt = Instant.parse("2026-08-29T09:20:00Z")
                )
            )
        ).ownership
        val decision = assertIs<LearningDecisionInstallResult.Installed>(
            f.decisions.install(
                LearningDecision(
                    id = LearningDecisionId("decision-1"),
                    candidate = LearningCandidateReference(candidate.candidate.id, candidate.generation),
                    disposition = LearningDecisionDisposition.APPROVE,
                    rationale = "approved",
                    createdAt = Instant.parse("2026-08-29T09:21:00Z")
                )
            )
        ).ownership
        val policy = assertIs<LearningPolicyInstallResult.Installed>(
            f.policies.install(
                LearningPolicy(
                    id = LearningPolicyId("policy-1"),
                    rule = "allow controlled application",
                    createdAt = Instant.parse("2026-08-29T09:22:00Z")
                )
            )
        ).ownership
        val application = assertIs<LearningApplicationInstallResult.Installed>(
            f.applications.install(
                LearningApplicationIntent(
                    id = LearningApplicationId("application-1"),
                    decision = LearningDecisionReference(decision.decision.id, decision.generation),
                    policy = LearningPolicyReference(policy.policy.id, policy.generation),
                    target = target,
                    createdAt = Instant.parse("2026-08-29T09:23:00Z")
                )
            )
        ).ownership
        return InstalledApplication(application, target)
    }

    private fun prepare(
        f: Fixture,
        application: LearningApplicationOwnership,
        target: LearningApplicationTarget = application.intent.target
    ): LearningApplicationMutationOwnership = assertIs<LearningApplicationMutationPrepareResult.Prepared>(
        f.mutations.prepare(
            LearningApplicationMutationPlan(
                id = LearningApplicationMutationId("mutation-1"),
                application = LearningApplicationIntentReference(application.intent.id, application.generation),
                principal = principal,
                target = target,
                idempotencyKey = LearningApplicationIdempotencyKey("idem-1"),
                payload = LearningApplicationMutationPayload.Memory(
                    MemoryRecord(
                        id = MemoryRecordId("memory-1"),
                        provenance = MemoryProvenance(MemorySourceId("learning-application")),
                        content = "prepared payload",
                        createdAt = Instant.parse("2026-08-29T09:24:00Z")
                    )
                ),
                createdAt = Instant.parse("2026-08-29T09:25:00Z")
            )
        )
    ).ownership

    private fun registerCapability(f: Fixture) {
        assertIs<CapabilityOwnershipResult.Registered>(
            f.authority.registerCapability(
                CapabilityDescriptor(
                    id = LearningApplicationAuthorityContract.capability,
                    providerId = CapabilityProviderId("learning-application")
                )
            )
        )
    }

    private fun grant(f: Fixture, target: LearningApplicationTarget) {
        assertIs<DirectAuthorityGrantOwnershipResult.Registered>(
            f.authority.registerDirectGrant(
                DirectAuthorityGrant(
                    principal = principal,
                    capability = LearningApplicationAuthorityContract.capability,
                    scope = LearningApplicationAuthorityContract.scopeFor(target)
                )
            )
        )
    }

    private fun reference(ownership: LearningApplicationMutationOwnership) =
        LearningApplicationMutationReference(ownership.plan.id, ownership.generation)

    @Test
    fun exact_current_mutation_requires_fresh_preflight_and_authority() {
        val f = fixture()
        val application = installApplication(f).ownership
        val mutation = prepare(f, application)
        registerCapability(f)
        grant(f, LearningApplicationTarget.MEMORY)

        val ready = assertIs<LearningApplicationMutationAuthorizationResult.Ready>(
            f.gate.authorize(reference(mutation))
        )

        assertEquals(reference(mutation), ready.receipt.mutation)
        assertEquals(application.generation, ready.receipt.applicationAuthorization.preflight.application.generation)
        assertEquals(LearningApplicationTarget.MEMORY, ready.receipt.applicationAuthorization.preflight.target)
    }

    @Test
    fun stale_mutation_generation_is_rejected() {
        val f = fixture()
        val application = installApplication(f).ownership
        val mutation = prepare(f, application)

        val result = assertIs<LearningApplicationMutationAuthorizationResult.MutationRejected>(
            f.gate.authorize(
                LearningApplicationMutationReference(
                    mutation.plan.id,
                    LearningApplicationMutationGeneration(mutation.generation.value + 1L)
                )
            )
        )

        assertEquals(
            LearningApplicationMutationAuthorizationRejection.MUTATION_GENERATION_MISMATCH,
            result.reason
        )
    }

    @Test
    fun missing_authority_grant_is_denied_without_mutation_permission() {
        val f = fixture()
        val application = installApplication(f).ownership
        val mutation = prepare(f, application)
        registerCapability(f)

        assertIs<LearningApplicationMutationAuthorizationResult.AuthorityDenied>(
            f.gate.authorize(reference(mutation))
        )
    }

    @Test
    fun prepared_target_must_match_fresh_application_target() {
        val f = fixture()
        val application = installApplication(f, LearningApplicationTarget.KNOWLEDGE).ownership
        val mutation = prepare(f, application, LearningApplicationTarget.MEMORY)
        registerCapability(f)
        grant(f, LearningApplicationTarget.KNOWLEDGE)

        val rejected = assertIs<LearningApplicationMutationAuthorizationResult.MutationRejected>(
            f.gate.authorize(reference(mutation))
        )

        assertEquals(LearningApplicationMutationAuthorizationRejection.TARGET_MISMATCH, rejected.reason)
    }

    @Test
    fun stale_application_reference_is_preflight_rejected() {
        val f = fixture()
        val application = installApplication(f).ownership
        val mutation = prepare(f, application)
        assertEquals(true, application.remove())
        registerCapability(f)
        grant(f, LearningApplicationTarget.MEMORY)

        val rejected = assertIs<LearningApplicationMutationAuthorizationResult.PreflightRejected>(
            f.gate.authorize(reference(mutation))
        )

        assertEquals(LearningApplicationPreflightRejection.APPLICATION_MISSING, rejected.reason)
    }
}
