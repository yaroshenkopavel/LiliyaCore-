package pro.liliya.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.android.runtime.AndroidProductRuntimeAdmissionFailure
import pro.liliya.android.runtime.AndroidProductRuntimeAdmissionGate
import pro.liliya.android.runtime.AndroidProductRuntimeAdmissionResult
import pro.liliya.android.runtime.AndroidProductRuntimeStartupActiveDekPort
import pro.liliya.android.runtime.AndroidProductRuntimeStartupAdmissionPort
import pro.liliya.android.runtime.AndroidProductRuntimeStartupModelPort
import pro.liliya.android.runtime.AndroidProductRuntimeStartupPreparationResult
import pro.liliya.android.runtime.AndroidProductRuntimeStartupPreparedInputsPort
import pro.liliya.android.runtime.AndroidProductRuntimeStartupProvisioner
import pro.liliya.android.runtime.AndroidProductRuntimeStartupProvisioningPorts
import pro.liliya.android.runtime.AndroidProductRuntimeStartupProvisioningResult
import pro.liliya.android.runtime.AndroidProductRuntimeStartupSemanticPort
import pro.liliya.core.authority.AuthorityManager
import pro.liliya.core.authority.AuthorityPolicy
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.AuthorityScope
import pro.liliya.core.authority.CapabilityAuthorityComposition
import pro.liliya.core.authority.CapabilityId
import pro.liliya.core.authority.CapabilityOwnershipResult
import pro.liliya.core.authority.DirectAuthorityGrant
import pro.liliya.core.authority.DirectAuthorityGrantOwnershipResult
import pro.liliya.core.capability.CapabilityDescriptor
import pro.liliya.core.capability.CapabilityProviderId
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseAuthorityComposition
import pro.liliya.core.license.LicenseAuthorityRequest
import pro.liliya.core.license.LicenseDigestTestVerifier
import pro.liliya.core.license.LicenseEntitlement
import pro.liliya.core.license.LicenseEntitlementCanonicalCodec
import pro.liliya.core.license.LicenseFeature
import pro.liliya.core.license.LicenseId
import pro.liliya.core.license.LicenseKeyId
import pro.liliya.core.license.LicensePolicyContext
import pro.liliya.core.license.LicensePolicyRequest
import pro.liliya.core.license.LicenseProductId
import pro.liliya.core.license.LicenseReplaySequence
import pro.liliya.core.license.LicenseRevocationEpoch
import pro.liliya.core.license.LicenseSignedEnvelope
import pro.liliya.core.license.LicenseSubject
import pro.liliya.core.license.LicenseTrustedKeyResolver
import pro.liliya.core.license.LicenseTrustedVerificationKey
import pro.liliya.core.license.LicenseVerificationResult
import pro.liliya.core.license.LicenseVerifier
import pro.liliya.core.license.LicenseVersion
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

@RunWith(AndroidJUnit4::class)
class PhysicalProductionAuthorityAdmissionInstrumentedTest {

    @Test
    fun authority_denial_stops_provisioning_and_explicit_grant_admits_without_execution() {
        val foundation = foundation()
        val capabilityAuthority = CapabilityAuthorityComposition(
            foundation = foundation,
            now = { NOW }
        )
        val authorityManager = AuthorityManager(
            policy = AuthorityPolicy { request ->
                capabilityAuthority.authorize(
                    request = request,
                    context = foundation.rootContext(
                        operation = "physical-authority-admission",
                        component = "PhysicalProductionAuthorityAdmissionInstrumentedTest"
                    )
                )
            },
            observability = foundation.observability
        )
        val licenseAuthority = LicenseAuthorityComposition(
            foundation = foundation,
            authorityManager = authorityManager
        )
        val verified = verifiedLicense()
        val licenseRequest = LicensePolicyRequest(
            productId = PRODUCT_ID,
            feature = FEATURE,
            subject = SUBJECT
        )
        val policyContext = LicensePolicyContext(
            now = NOW,
            minimumRevocationEpoch = LicenseRevocationEpoch(0),
            minimumReplaySequence = LicenseReplaySequence(1)
        )
        val authorityRequest = LicenseAuthorityRequest(
            principal = PRINCIPAL,
            capability = CAPABILITY,
            scope = SCOPE
        )

        val denied = assertIs<AndroidProductRuntimeAdmissionResult.Rejected>(
            AndroidProductRuntimeAdmissionGate.admit(
                composition = licenseAuthority,
                verified = verified,
                licenseRequest = licenseRequest,
                policyContext = policyContext,
                authorityRequest = authorityRequest
            )
        )
        assertEquals(AndroidProductRuntimeAdmissionFailure.AUTHORITY_DENIED, denied.reason)

        var postAdmissionCalls = 0
        val provisioning = AndroidProductRuntimeStartupProvisioner.prepare(
            AndroidProductRuntimeStartupProvisioningPorts(
                admission = AndroidProductRuntimeStartupAdmissionPort {
                    AndroidProductRuntimeAdmissionGate.admit(
                        composition = licenseAuthority,
                        verified = verified,
                        licenseRequest = licenseRequest,
                        policyContext = policyContext,
                        authorityRequest = authorityRequest
                    )
                },
                activeDek = AndroidProductRuntimeStartupActiveDekPort {
                    postAdmissionCalls += 1
                    AndroidProductRuntimeStartupPreparationResult.Rejected
                },
                semantic = AndroidProductRuntimeStartupSemanticPort {
                    postAdmissionCalls += 1
                    AndroidProductRuntimeStartupPreparationResult.Rejected
                },
                model = AndroidProductRuntimeStartupModelPort {
                    postAdmissionCalls += 1
                    AndroidProductRuntimeStartupPreparationResult.Rejected
                },
                preparedInputs = AndroidProductRuntimeStartupPreparedInputsPort { _, _, _ ->
                    postAdmissionCalls += 1
                    AndroidProductRuntimeStartupPreparationResult.Rejected
                }
            )
        )
        assertIs<AndroidProductRuntimeStartupProvisioningResult.AdmissionRejected>(provisioning)
        assertEquals(0, postAdmissionCalls)

        val capabilityOwnership = assertIs<CapabilityOwnershipResult.Registered>(
            capabilityAuthority.registerCapability(
                CapabilityDescriptor(
                    id = CAPABILITY,
                    providerId = CapabilityProviderId("physical-production-authority")
                )
            )
        ).ownership
        val grantOwnership = assertIs<DirectAuthorityGrantOwnershipResult.Registered>(
            capabilityAuthority.registerDirectGrant(
                DirectAuthorityGrant(
                    principal = PRINCIPAL,
                    capability = CAPABILITY,
                    scope = SCOPE,
                    expiresAt = NOW.plusSeconds(3600)
                )
            )
        ).ownership

        try {
            assertIs<AndroidProductRuntimeAdmissionResult.Admitted>(
                AndroidProductRuntimeAdmissionGate.admit(
                    composition = licenseAuthority,
                    verified = verified,
                    licenseRequest = licenseRequest,
                    policyContext = policyContext,
                    authorityRequest = authorityRequest
                )
            )

            println(
                "LILIYA_PRODUCTION_AUTHORITY_ADMISSION=" +
                    "{\"authorityDeniedWithoutGrant\":true," +
                    "\"provisioningStoppedBeforeDekSemanticModel\":true," +
                    "\"explicitGrantAdmitted\":true," +
                    "\"authorityExecutionStarted\":false}"
            )
        } finally {
            grantOwnership.revoke()
            capabilityOwnership.unregister()
        }
    }

    private fun verifiedLicense(): LicenseVerificationResult.Verified {
        val algorithm = LicenseAlgorithm("TEST-SHA256")
        val keyId = LicenseKeyId("physical-authority-test-key")
        val key = LicenseTrustedVerificationKey.of(
            keyId = keyId,
            algorithm = algorithm,
            material = byteArrayOf(11, 22, 33, 44, 55, 66, 77, 88)
        )
        val entitlement = LicenseEntitlement(
            id = LicenseId("physical-authority-test-license"),
            subject = SUBJECT,
            productId = PRODUCT_ID,
            features = setOf(FEATURE),
            version = LicenseVersion(1),
            signingKeyId = keyId,
            issuedAt = NOW.minusSeconds(120),
            notBefore = NOW.minusSeconds(60),
            expiresAt = null,
            offlineLeaseUntil = null,
            revocationEpoch = LicenseRevocationEpoch(0),
            replaySequence = LicenseReplaySequence(1)
        )
        val payload = LicenseEntitlementCanonicalCodec.encode(entitlement)
        val envelope = LicenseSignedEnvelope(
            schemaVersion = LicenseVersion(1),
            algorithm = algorithm,
            signingKeyId = keyId,
            payload = payload,
            signature = LicenseDigestTestVerifier.signForTest(key, payload)
        )
        return assertIs(
            LicenseVerifier(
                supportedSchemaVersion = LicenseVersion(1),
                supportedAlgorithms = setOf(algorithm),
                trustedKeys = LicenseTrustedKeyResolver { requested ->
                    key.takeIf { it.keyId == requested }
                },
                signatureVerifier = LicenseDigestTestVerifier
            ).verify(envelope)
        )
    }

    private fun foundation(): FoundationComposition {
        val logs = InMemoryLogWriter()
        val diagnostics = InMemoryDiagnosticSink()
        var next = 0
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(diagnostics),
            loggerProvider = LoggerProvider { context ->
                StructuredLogger(context, logs)
            },
            correlationIds = CorrelationIdGenerator {
                "physical-authority-admission-" + (++next)
            }
        )
    }

    companion object {
        private val NOW = Instant.parse("2026-10-03T19:00:00Z")
        private val PRODUCT_ID = LicenseProductId("liliya-pro")
        private val FEATURE = LicenseFeature("model.local")
        private val SUBJECT = LicenseSubject("physical-authority-test-subject")
        private val PRINCIPAL = AuthorityPrincipal("liliya-product-runtime")
        private val CAPABILITY = CapabilityId("runtime.start")
        private val SCOPE = AuthorityScope.GLOBAL
    }
}
