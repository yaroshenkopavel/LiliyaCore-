package pro.liliya.core.cognitive

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
import pro.liliya.core.knowledge.KnowledgeComposition
import pro.liliya.core.learning.LearningApplicationAuthorityContract
import pro.liliya.core.learning.LearningApplicationComposition
import pro.liliya.core.learning.LearningApplicationMutationApplier
import pro.liliya.core.learning.LearningApplicationMutationAuthorizationGate
import pro.liliya.core.learning.LearningApplicationMutationComposition
import pro.liliya.core.learning.LearningApplicationAuthorizer
import pro.liliya.core.learning.LearningApplicationPreflightValidator
import pro.liliya.core.learning.LearningApplicationTarget
import pro.liliya.core.learning.LearningCandidate
import pro.liliya.core.learning.LearningCandidateId
import pro.liliya.core.learning.LearningComposition
import pro.liliya.core.learning.LearningDecisionComposition
import pro.liliya.core.learning.LearningInstallResult
import pro.liliya.core.learning.LearningOrigin
import pro.liliya.core.learning.LearningPolicy
import pro.liliya.core.learning.LearningPolicyComposition
import pro.liliya.core.learning.LearningPolicyId
import pro.liliya.core.learning.LearningPolicyInstallResult
import pro.liliya.core.learning.LearningPolicyReference
import pro.liliya.core.learning.LearningSourceId
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.memory.MemoryComposition
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CognitiveGovernedLearningCompositionContractTest {
    @Test
    fun public_composition_applies_through_authority_and_logs_only_structural_state() {
        val secretProposal = "PUBLIC-BOUNDARY-PRIVATE-PROPOSAL"
        val secretPolicy = "PUBLIC-BOUNDARY-PRIVATE-POLICY"
        val secretRationale = "PUBLIC-BOUNDARY-PRIVATE-RATIONALE"
        val secretContent = "PUBLIC-BOUNDARY-PRIVATE-CONTENT"
        val logs = InMemoryLogWriter()
        val diagnostics = InMemoryDiagnosticSink()
        val correlation = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(diagnostics),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "governed-public-${correlation.incrementAndGet()}" }
        )
        val learning = LearningComposition(foundation)
        val policies = LearningPolicyComposition(foundation)
        val decisions = LearningDecisionComposition(foundation)
        val applications = LearningApplicationComposition(foundation)
        val mutations = LearningApplicationMutationComposition(foundation)
        val authority = CapabilityAuthorityComposition(foundation)
        val memory = MemoryComposition(foundation)
        val knowledge = KnowledgeComposition(foundation)
        val principal = AuthorityPrincipal("cognitive-learning-system")

        val candidate = assertIs<LearningInstallResult.Installed>(
            learning.install(
                LearningCandidate(
                    id = LearningCandidateId("public-candidate"),
                    origin = LearningOrigin.Declared(LearningSourceId("cognitive-test")),
                    proposal = secretProposal,
                    createdAt = Instant.parse("2026-09-01T18:20:00Z")
                )
            )
        ).ownership
        val policy = assertIs<LearningPolicyInstallResult.Installed>(
            policies.install(
                LearningPolicy(
                    id = LearningPolicyId("public-policy"),
                    rule = secretPolicy,
                    createdAt = Instant.parse("2026-09-01T18:21:00Z")
                )
            )
        ).ownership

        assertIs<CapabilityOwnershipResult.Registered>(
            authority.registerCapability(
                CapabilityDescriptor(
                    id = LearningApplicationAuthorityContract.capability,
                    providerId = CapabilityProviderId("cognitive-learning")
                )
            )
        )
        assertIs<DirectAuthorityGrantOwnershipResult.Registered>(
            authority.registerDirectGrant(
                DirectAuthorityGrant(
                    principal = principal,
                    capability = LearningApplicationAuthorityContract.capability,
                    scope = LearningApplicationAuthorityContract.scopeFor(
                        LearningApplicationTarget.MEMORY
                    )
                )
            )
        )

        val preflight = LearningApplicationPreflightValidator(
            applications,
            decisions,
            learning,
            policies
        )
        val authorizer = LearningApplicationAuthorizer(preflight, authority)
        val gate = LearningApplicationMutationAuthorizationGate(mutations, authorizer)
        val applier = LearningApplicationMutationApplier(mutations, gate, memory, knowledge)
        val perKind = mutableMapOf<CognitiveArtifactIdKind, Int>()
        val governed = CognitiveGovernedLearningComposition(
            foundation = foundation,
            scope = CognitiveRuntimeScopeId("public-governed-scope"),
            learning = learning,
            policies = policies,
            policyReference = LearningPolicyReference(policy.policy.id, policy.generation),
            governance = CognitiveLearningGovernancePort {
                CognitiveLearningGovernanceResult.Approved(
                    target = LearningApplicationTarget.MEMORY,
                    rationale = secretRationale
                )
            },
            decisions = decisions,
            materialization = CognitiveLearningApplicationMaterializationPort {
                CognitiveLearningApplicationMaterializationResult.Succeeded(secretContent)
            },
            applications = applications,
            mutations = mutations,
            mutationApplier = applier,
            principal = principal,
            allowedTargets = listOf(LearningApplicationTarget.MEMORY),
            artifactIds = CognitiveArtifactIdSource { kind ->
                val next = (perKind[kind] ?: 0) + 1
                perKind[kind] = next
                "public-${kind.name.lowercase()}-$next"
            },
            timestamps = CognitiveTimestampSource { Instant.parse("2026-09-01T18:22:00Z") }
        )

        assertIs<CognitiveGovernedLearningResult.Applied>(
            governed.process(CognitiveLearningReference(candidate.candidate.id, candidate.generation))
        )
        assertTrue(memory.snapshotEntries().size == 1)

        val rendered = logs.snapshot().joinToString("\n") + "\n" +
            diagnostics.snapshot().joinToString("\n")
        listOf(secretProposal, secretPolicy, secretRationale, secretContent).forEach { secret ->
            assertFalse(rendered.contains(secret))
        }
        assertTrue(rendered.contains("COGNITIVE_GOVERNED_LEARNING_APPLIED"))
    }
}
