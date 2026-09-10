package pro.liliya.android.runtime

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import org.junit.Test
import pro.liliya.android.cognitivestorage.AndroidCognitiveStorageFirstRunKeySetupRequest
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.AuthorityScope
import pro.liliya.core.authority.CapabilityId
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.encryption.CognitiveDekGeneration
import pro.liliya.core.encryption.CognitiveDekId
import pro.liliya.core.encryption.CognitiveDekReference
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseAuthorityRequest
import pro.liliya.core.license.LicenseCanonicalPayload
import pro.liliya.core.license.LicenseFeature
import pro.liliya.core.license.LicenseKeyId
import pro.liliya.core.license.LicensePolicyContext
import pro.liliya.core.license.LicensePolicyRequest
import pro.liliya.core.license.LicenseProductId
import pro.liliya.core.license.LicenseRevocationEpoch
import pro.liliya.core.license.LicenseSignature
import pro.liliya.core.license.LicenseSignedEnvelope
import pro.liliya.core.license.LicenseSubject
import pro.liliya.core.license.LicenseTrustedKeyResolver
import pro.liliya.core.license.LicenseVersion
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.protectedmodel.LargeProtectedModelPackageBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelResourceBudgets

class AndroidProductRuntimeFirstRunProductInputFactoryContractTest {
    @Test
    fun exact_resolved_inputs_are_preserved_without_substitution() {
        val trust = trust()
        val admission = admission()
        val keyRequest = AndroidCognitiveStorageFirstRunKeySetupRequest.RestoreExact(
            CognitiveDekReference(
                CognitiveDekId("cognitive-dek"),
                CognitiveDekGeneration(4)
            )
        )
        val budgets = budgets()

        val result = AndroidProductRuntimeFirstRunProductInputFactory.resolve(
            trust = AndroidProductRuntimeStartupTrustInputFactoryResult.Ready(trust),
            admission = AndroidProductRuntimeStartupAdmissionInputResult.Ready(admission),
            keyChoice = AndroidProductRuntimeFirstRunKeyChoiceResult.Ready(keyRequest),
            budgets = AndroidProductRuntimeProtectedModelBudgetResult.Ready(budgets)
        )

        val ready =
            assertIs<AndroidProductRuntimeResolvedFirstRunProductInputResult.Ready>(result)
        assertSame(trust, ready.inputs.trust)
        assertSame(admission, ready.inputs.admission)
        assertSame(keyRequest, ready.inputs.keyRequest)
        assertSame(budgets, ready.inputs.budgets)
    }

    @Test
    fun trust_rejection_is_preserved() {
        val result = AndroidProductRuntimeFirstRunProductInputFactory.resolve(
            trust = AndroidProductRuntimeStartupTrustInputFactoryResult.Rejected,
            admission = AndroidProductRuntimeStartupAdmissionInputResult.Ready(admission()),
            keyChoice = AndroidProductRuntimeFirstRunKeyChoiceResult.Ready(keyRequest()),
            budgets = AndroidProductRuntimeProtectedModelBudgetResult.Ready(budgets())
        )

        assertEquals(
            AndroidProductRuntimeFirstRunProductInputFailure.TRUST_INPUT_REJECTED,
            assertIs<AndroidProductRuntimeResolvedFirstRunProductInputResult.Rejected>(result)
                .reason
        )
    }

    @Test
    fun admission_rejection_is_preserved() {
        val result = AndroidProductRuntimeFirstRunProductInputFactory.resolve(
            trust = AndroidProductRuntimeStartupTrustInputFactoryResult.Ready(trust()),
            admission = AndroidProductRuntimeStartupAdmissionInputResult.Rejected,
            keyChoice = AndroidProductRuntimeFirstRunKeyChoiceResult.Ready(keyRequest()),
            budgets = AndroidProductRuntimeProtectedModelBudgetResult.Ready(budgets())
        )

        assertEquals(
            AndroidProductRuntimeFirstRunProductInputFailure.ADMISSION_INPUT_REJECTED,
            assertIs<AndroidProductRuntimeResolvedFirstRunProductInputResult.Rejected>(result)
                .reason
        )
    }

    @Test
    fun key_choice_rejection_is_preserved() {
        val result = AndroidProductRuntimeFirstRunProductInputFactory.resolve(
            trust = AndroidProductRuntimeStartupTrustInputFactoryResult.Ready(trust()),
            admission = AndroidProductRuntimeStartupAdmissionInputResult.Ready(admission()),
            keyChoice = AndroidProductRuntimeFirstRunKeyChoiceResult.Rejected,
            budgets = AndroidProductRuntimeProtectedModelBudgetResult.Ready(budgets())
        )

        assertEquals(
            AndroidProductRuntimeFirstRunProductInputFailure.COGNITIVE_KEY_CHOICE_REJECTED,
            assertIs<AndroidProductRuntimeResolvedFirstRunProductInputResult.Rejected>(result)
                .reason
        )
    }

    @Test
    fun protected_model_budget_rejection_is_preserved() {
        val result = AndroidProductRuntimeFirstRunProductInputFactory.resolve(
            trust = AndroidProductRuntimeStartupTrustInputFactoryResult.Ready(trust()),
            admission = AndroidProductRuntimeStartupAdmissionInputResult.Ready(admission()),
            keyChoice = AndroidProductRuntimeFirstRunKeyChoiceResult.Ready(keyRequest()),
            budgets = AndroidProductRuntimeProtectedModelBudgetResult.Rejected
        )

        assertEquals(
            AndroidProductRuntimeFirstRunProductInputFailure.PROTECTED_MODEL_BUDGET_REJECTED,
            assertIs<AndroidProductRuntimeResolvedFirstRunProductInputResult.Rejected>(result)
                .reason
        )
    }

    private fun trust(): AndroidProductRuntimeStartupTrustInput =
        AndroidProductRuntimeStartupTrustInput(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context ->
                StructuredLogger(context, InMemoryLogWriter())
            },
            supportedLicenseSchemaVersion = LicenseVersion(1),
            trustedKeys = LicenseTrustedKeyResolver { null },
            licenseEnvelope = LicenseSignedEnvelope(
                schemaVersion = LicenseVersion(1),
                algorithm = LicenseAlgorithm("ECDSA-P256-SHA256"),
                signingKeyId = LicenseKeyId("primary"),
                payload = LicenseCanonicalPayload.of(byteArrayOf(1, 2, 3)),
                signature = LicenseSignature.of(byteArrayOf(4, 5, 6))
            )
        )

    private fun admission(): AndroidProductRuntimeStartupAdmissionContracts =
        AndroidProductRuntimeStartupAdmissionContracts(
            licenseRequest = LicensePolicyRequest(
                productId = LicenseProductId("liliya-core"),
                feature = LicenseFeature("runtime"),
                subject = LicenseSubject("device-owner")
            ),
            policyContext = LicensePolicyContext(
                now = Instant.parse("2026-09-10T00:00:00Z"),
                minimumRevocationEpoch = LicenseRevocationEpoch(0),
                minimumReplaySequence = null,
                suspiciousTimeOrReplayState = false
            ),
            authorityRequest = LicenseAuthorityRequest(
                principal = AuthorityPrincipal("liliya"),
                capability = CapabilityId("runtime.start"),
                scope = AuthorityScope("global")
            )
        )

    private fun keyRequest(): AndroidCognitiveStorageFirstRunKeySetupRequest =
        AndroidCognitiveStorageFirstRunKeySetupRequest.RestoreExact(
            CognitiveDekReference(
                CognitiveDekId("cognitive-dek"),
                CognitiveDekGeneration(4)
            )
        )

    private fun budgets(): AndroidProductRuntimeProtectedModelBudgets =
        AndroidProductRuntimeProtectedModelBudgets(
            manifest = LargeProtectedModelResourceBudgets(
                maxTotalPlaintextBytes = 1_000_000,
                maxTotalCiphertextBodyBytes = 1_000_000,
                maxTotalProtectedPayloadBytes = 1_100_000,
                maxSegmentCount = 64,
                minNonFinalSegmentPlaintextBytes = 4_096,
                maxSegmentPlaintextBytes = 65_536,
                maxSegmentCiphertextBodyBytes = 65_536,
                maxStructuralIdentifierChars = 256,
                maxCanonicalManifestBytes = 1_000_000
            ),
            packageEnvelope = LargeProtectedModelPackageBudgets(
                maxModelProfileIdChars = 128,
                maxSignerIdChars = 128,
                maxCanonicalSignedManifestBytes = 1_100_000
            ),
            container = ProductProtectedModelLocalPackageBudgets(
                maxContainerBytes = 1_200_000,
                maxSignatureBytes = 4_096
            )
        )
}
