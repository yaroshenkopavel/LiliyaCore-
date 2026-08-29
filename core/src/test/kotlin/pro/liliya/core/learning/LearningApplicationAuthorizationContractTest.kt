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
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LearningApplicationAuthorizationContractTest {
    private data class Fixture(
        val candidates: LearningComposition,
        val decisions: LearningDecisionComposition,
        val policies: LearningPolicyComposition,
        val applications: LearningApplicationComposition,
        val authority: CapabilityAuthorityComposition,
        val authorizer: LearningApplicationAuthorizer
    )

    private data class InstalledChain(
        val application: LearningApplicationOwnership,
        val target: LearningApplicationTarget
    )

    private val principal = AuthorityPrincipal("learning-controller")

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "authorization-${sequence.incrementAndGet()}" }
        )
        val candidates = LearningComposition(foundation)
        val decisions = LearningDecisionComposition(foundation)
        val policies = LearningPolicyComposition(foundation)
        val applications = LearningApplicationComposition(foundation)
        val authority = CapabilityAuthorityComposition(foundation)
        val preflight = LearningApplicationPreflightValidator(
            applications = applications,
            decisions = decisions,
            candidates = candidates,
            policies = policies
        )
        return Fixture(
            candidates = candidates,
            decisions = decisions,
            policies = policies,
            applications = applications,
            authority = authority,
            authorizer = LearningApplicationAuthorizer(preflight, authority)
        )
    }

    private fun installChain(
        f: Fixture,
        target: LearningApplicationTarget = LearningApplicationTarget.MEMORY
    ): InstalledChain {
        val candidate = assertIs<LearningInstallResult.Installed>(
            f.candidates.install(
                LearningCandidate(
                    id = LearningCandidateId("candidate-1"),
                    origin = LearningOrigin.Declared(LearningSourceId("test")),
                    proposal = "sensitive proposal",
                    createdAt = Instant.parse("2026-08-29T00:00:00Z")
                )
            )
        ).ownership
        val decision = assertIs<LearningDecisionInstallResult.Installed>(
            f.decisions.install(
                LearningDecision(
                    id = LearningDecisionId("decision-1"),
                    candidate = LearningCandidateReference(candidate.candidate.id, candidate.generation),
                    disposition = LearningDecisionDisposition.APPROVE,
                    rationale = "sensitive rationale",
                    createdAt = Instant.parse("2026-08-29T00:01:00Z")
                )
            )
        ).ownership
        val policy = assertIs<LearningPolicyInstallResult.Installed>(
            f.policies.install(
                LearningPolicy(
                    id = LearningPolicyId("policy-1"),
                    rule = "sensitive rule",
                    createdAt = Instant.parse("2026-08-29T00:02:00Z")
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
                    createdAt = Instant.parse("2026-08-29T00:03:00Z")
                )
            )
        ).ownership
        return InstalledChain(application, target)
    }

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

    @Test
    fun exact_preflight_and_exact_target_scope_grant_authorize_without_applying() {
        val f = fixture()
        val chain = installChain(f, LearningApplicationTarget.MEMORY)
        registerCapability(f)
        grant(f, LearningApplicationTarget.MEMORY)

        val result = assertIs<LearningApplicationAuthorizationResult.Authorized>(
            f.authorizer.authorize(
                LearningApplicationIntentReference(chain.application.intent.id, chain.application.generation),
                principal
            )
        )

        assertEquals(principal, result.receipt.principal)
        assertEquals(LearningApplicationAuthorityContract.capability, result.receipt.capability)
        assertEquals(
            LearningApplicationAuthorityContract.scopeFor(LearningApplicationTarget.MEMORY),
            result.receipt.scope
        )
        assertEquals(chain.application.intent.id, result.receipt.preflight.application.applicationId)
        assertEquals(LearningApplicationTarget.MEMORY, result.receipt.preflight.target)
    }

    @Test
    fun missing_grant_is_denied() {
        val f = fixture()
        val chain = installChain(f)
        registerCapability(f)

        assertIs<LearningApplicationAuthorizationResult.Denied>(
            f.authorizer.authorize(
                LearningApplicationIntentReference(chain.application.intent.id, chain.application.generation),
                principal
            )
        )
    }

    @Test
    fun grant_for_other_target_scope_does_not_authorize() {
        val f = fixture()
        val chain = installChain(f, LearningApplicationTarget.MEMORY)
        registerCapability(f)
        grant(f, LearningApplicationTarget.KNOWLEDGE)

        assertIs<LearningApplicationAuthorizationResult.Denied>(
            f.authorizer.authorize(
                LearningApplicationIntentReference(chain.application.intent.id, chain.application.generation),
                principal
            )
        )
    }

    @Test
    fun stale_application_reference_is_preflight_rejected_before_authorization() {
        val f = fixture()
        val chain = installChain(f)

        val result = assertIs<LearningApplicationAuthorizationResult.PreflightRejected>(
            f.authorizer.authorize(
                LearningApplicationIntentReference(
                    chain.application.intent.id,
                    LearningApplicationGeneration(chain.application.generation.value + 1L)
                ),
                principal
            )
        )

        assertEquals(LearningApplicationPreflightRejection.APPLICATION_GENERATION_MISMATCH, result.reason)
    }
}
