package pro.liliya.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.URL
import java.time.Instant
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.android.devicekey.AndroidActivationDeviceBindingProvider
import pro.liliya.android.devicekey.AndroidActivationDeviceBindingResult
import pro.liliya.android.runtime.AndroidProductRuntimeAdmissionFailure
import pro.liliya.android.runtime.AndroidProductRuntimeAdmissionGate
import pro.liliya.android.runtime.AndroidProductRuntimeAdmissionResult
import pro.liliya.android.runtime.AndroidProductRuntimeStartupActiveDekPort
import pro.liliya.android.runtime.AndroidProductRuntimeStartupAdmissionPort
import pro.liliya.android.runtime.AndroidProductRuntimeStartupAuthorityAssemblyResult
import pro.liliya.android.runtime.AndroidProductRuntimeStartupAuthorityGrantAssembly
import pro.liliya.android.runtime.AndroidProductRuntimeStartupAuthorityPlan
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
import pro.liliya.core.authority.DirectAuthorityGrant
import pro.liliya.core.capability.CapabilityDescriptor
import pro.liliya.core.capability.CapabilityProviderId
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.license.JcaEcdsaP256LicenseSignatureVerifier
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseAuthorityComposition
import pro.liliya.core.license.LicenseAuthorityRequest
import pro.liliya.core.license.LicenseDeviceBindingReferenceFactory
import pro.liliya.core.license.LicenseKeyId
import pro.liliya.core.license.LicensePolicyContext
import pro.liliya.core.license.LicensePolicyRequest
import pro.liliya.core.license.LicenseProductId
import pro.liliya.core.license.LicenseTrustedKeyResolver
import pro.liliya.core.license.LicenseTrustedVerificationKey
import pro.liliya.core.license.LicenseVerificationResult
import pro.liliya.core.license.LicenseVerifier
import pro.liliya.core.license.LicenseVersion
import pro.liliya.core.licensetransport.ActivationRedemptionHttpClient
import pro.liliya.core.licensetransport.ActivationRedemptionHttpRequest
import pro.liliya.core.licensetransport.ActivationRedemptionHttpResult
import pro.liliya.core.licensetransport.LicenseHttpTlsTrust
import pro.liliya.core.licensetransport.LicenseHttpTransportConfig
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

/**
 * Physical production Authority/admission acceptance.
 *
 * Fresh one-shot Activation Code -> real private-CA Licensing Service -> real ECDSA signed,
 * one-device-bound unlimited-offline License -> explicit startup Authority plan ->
 * fail-closed denial before grant -> admission after exact grant.
 *
 * This test intentionally never starts DEK, semantic, model or Execution.
 */
@RunWith(AndroidJUnit4::class)
class PhysicalProductionAuthorityAdmissionInstrumentedTest {

    @Test
    fun real_signed_license_denies_without_authority_then_admits_exact_explicit_grant_without_execution() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val context = instrumentation.targetContext.applicationContext

        val endpoint = URL(required(args.getString(ARG_ENDPOINT)))
        assertEquals("https", endpoint.protocol)

        val caBytes = Base64.getDecoder().decode(required(args.getString(ARG_CA_BASE64)))
        val tlsTrust = try {
            LicenseHttpTlsTrust.ofCertificate(caBytes)
        } finally {
            caBytes.fill(0)
        }

        val binding = assertIs<AndroidActivationDeviceBindingResult.Ready>(
            AndroidActivationDeviceBindingProvider(context).loadOrCreate()
        ).binding
        val expectedBinding = LicenseDeviceBindingReferenceFactory.create(
            installationId = binding.installationId,
            deviceKeyFingerprint = binding.deviceKeyFingerprint
        )

        val activationCodeFile = java.io.File(context.filesDir, ACTIVATION_CODE_FILE)
        assertTrue(activationCodeFile.isFile)
        val activationCode = activationCodeFile.readText(Charsets.UTF_8).trim()
        assertTrue(activationCode.isNotBlank())
        assertTrue(activationCodeFile.delete())
        assertTrue(!activationCodeFile.exists())

        val redemption = ActivationRedemptionHttpClient(
            LicenseHttpTransportConfig(
                endpoint = endpoint,
                connectTimeoutMillis = 10_000,
                readTimeoutMillis = 60_000,
                developmentAllowInsecureHttp = false,
                tlsTrust = tlsTrust
            )
        ).redeem(
            ActivationRedemptionHttpRequest(
                activationCode = activationCode,
                attemptId = ProductionAndroidActivationAttemptIdentity.loadOrCreate(context),
                installationId = binding.installationId,
                deviceKeyFingerprint = binding.deviceKeyFingerprint
            )
        )

        val activated = assertIs<ActivationRedemptionHttpResult.Activated>(redemption)
        val publicKey = Base64.getDecoder().decode(
            required(args.getString(ARG_LICENSE_KEY_BASE64))
        )
        val trusted = try {
            LicenseTrustedVerificationKey.of(
                keyId = activated.license.signingKeyId,
                algorithm = LicenseAlgorithm(ALGORITHM),
                material = publicKey
            )
        } finally {
            publicKey.fill(0)
        }

        val verified = assertIs<LicenseVerificationResult.Verified>(
            LicenseVerifier(
                supportedSchemaVersion = LicenseVersion(1),
                supportedAlgorithms = setOf(LicenseAlgorithm(ALGORITHM)),
                trustedKeys = LicenseTrustedKeyResolver { requested: LicenseKeyId ->
                    trusted.takeIf { it.keyId == requested }
                },
                signatureVerifier = JcaEcdsaP256LicenseSignatureVerifier
            ).verify(activated.license)
        )

        assertEquals(LicenseProductId(required(args.getString(ARG_PRODUCT))), verified.entitlement.productId)
        assertEquals(activated.subject, verified.entitlement.subject.value)
        assertEquals(expectedBinding, assertNotNull(verified.entitlement.deviceBindingReference))
        assertNull(verified.entitlement.expiresAt)
        assertNull(verified.entitlement.offlineLeaseUntil)
        assertTrue(verified.entitlement.features.any { it.value == GRANTED_CAPABILITY })

        val foundation = foundation()
        val policyNow = Instant.now()
        val capabilityAuthority = CapabilityAuthorityComposition(
            foundation = foundation,
            now = { policyNow }
        )
        val authorityManager = AuthorityManager(
            policy = AuthorityPolicy { request ->
                capabilityAuthority.authorize(
                    request = request,
                    context = foundation.rootContext(
                        operation = "physical-production-authority-admission",
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

        val feature = verified.entitlement.features.first { it.value == GRANTED_CAPABILITY }
        val principal = AuthorityPrincipal(PRINCIPAL)
        val grantedCapability = CapabilityId(GRANTED_CAPABILITY)
        val deniedCapability = CapabilityId(DENIED_CAPABILITY)
        val scope = AuthorityScope.GLOBAL
        val licenseRequest = LicensePolicyRequest(
            productId = verified.entitlement.productId,
            feature = feature,
            subject = verified.entitlement.subject
        )
        val policyContext = LicensePolicyContext(
            now = policyNow,
            minimumRevocationEpoch = verified.entitlement.revocationEpoch,
            minimumReplaySequence = verified.entitlement.replaySequence,
            requiredDeviceBindingReference = expectedBinding
        )

        val denied = assertIs<AndroidProductRuntimeAdmissionResult.Rejected>(
            AndroidProductRuntimeAdmissionGate.admit(
                composition = licenseAuthority,
                verified = verified,
                licenseRequest = licenseRequest,
                policyContext = policyContext,
                authorityRequest = LicenseAuthorityRequest(
                    principal = principal,
                    capability = grantedCapability,
                    scope = scope
                )
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
                        authorityRequest = LicenseAuthorityRequest(
                            principal = principal,
                            capability = grantedCapability,
                            scope = scope
                        )
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

        val installed = assertIs<AndroidProductRuntimeStartupAuthorityAssemblyResult.Ready>(
            AndroidProductRuntimeStartupAuthorityGrantAssembly.install(
                authority = capabilityAuthority,
                plan = AndroidProductRuntimeStartupAuthorityPlan(
                    capabilities = listOf(
                        CapabilityDescriptor(
                            id = grantedCapability,
                            providerId = CapabilityProviderId(PROVIDER)
                        )
                    ),
                    directGrants = listOf(
                        DirectAuthorityGrant(
                            principal = principal,
                            capability = grantedCapability,
                            scope = scope
                        )
                    )
                )
            )
        )

        try {
            assertIs<AndroidProductRuntimeAdmissionResult.Admitted>(
                AndroidProductRuntimeAdmissionGate.admit(
                    composition = licenseAuthority,
                    verified = verified,
                    licenseRequest = licenseRequest,
                    policyContext = policyContext,
                    authorityRequest = LicenseAuthorityRequest(
                        principal = principal,
                        capability = grantedCapability,
                        scope = scope
                    )
                )
            )

            val negative = assertIs<AndroidProductRuntimeAdmissionResult.Rejected>(
                AndroidProductRuntimeAdmissionGate.admit(
                    composition = licenseAuthority,
                    verified = verified,
                    licenseRequest = licenseRequest,
                    policyContext = policyContext,
                    authorityRequest = LicenseAuthorityRequest(
                        principal = principal,
                        capability = deniedCapability,
                        scope = scope
                    )
                )
            )
            assertEquals(AndroidProductRuntimeAdmissionFailure.AUTHORITY_DENIED, negative.reason)

            println(
                "LILIYA_PRODUCTION_AUTHORITY_ADMISSION=" +
                    "{\"https\":true," +
                    "\"realSignedLicense\":true," +
                    "\"oneDeviceBinding\":true," +
                    "\"unlimitedOffline\":true," +
                    "\"authorityDeniedWithoutGrant\":true," +
                    "\"provisioningStoppedBeforeDekSemanticModel\":true," +
                    "\"explicitAuthorityPlanInstalled\":true," +
                    "\"grantedCapabilityAdmitted\":true," +
                    "\"ungrantedCapabilityDenied\":true," +
                    "\"authorityExecutionStarted\":false}"
            )
        } finally {
            installed.ownership.directGrants.asReversed().forEach { runCatching { it.revoke() } }
            installed.ownership.capabilities.asReversed().forEach { runCatching { it.unregister() } }
        }
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
                "physical-production-authority-" + (++next)
            }
        )
    }

    private fun required(value: String?): String =
        value?.takeIf { it.isNotBlank() }
            ?: error("missing physical production Authority/admission argument")

    private companion object {
        const val ARG_ENDPOINT = "activationEndpoint"
        const val ARG_CA_BASE64 = "activationCaBase64"
        const val ARG_LICENSE_KEY_BASE64 = "activationLicenseKeyDerBase64"
        const val ARG_PRODUCT = "activationProductId"
        const val ACTIVATION_CODE_FILE = "physical-activation-code.once"
        const val ALGORITHM = "ECDSA-P256-SHA256"
        const val PRINCIPAL = "liliya-product-runtime"
        const val GRANTED_CAPABILITY = "model.local"
        const val DENIED_CAPABILITY = "runtime.ungranted"
        const val PROVIDER = "production-physical-authority-acceptance"
    }
}
