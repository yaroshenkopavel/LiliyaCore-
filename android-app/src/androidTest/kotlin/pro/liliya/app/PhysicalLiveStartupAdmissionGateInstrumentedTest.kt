package pro.liliya.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
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
import pro.liliya.android.runtime.AndroidProductRuntimeStartupProvisioningFailure
import pro.liliya.android.runtime.AndroidProductRuntimeStartupProvisioningPorts
import pro.liliya.android.runtime.AndroidProductRuntimeStartupProvisioningResult
import pro.liliya.android.runtime.AndroidProductRuntimeStartupSemanticPort
import pro.liliya.core.authority.AuthorityManager
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.AuthorityScope
import pro.liliya.core.authority.CapabilityAuthorityComposition
import pro.liliya.core.authority.CapabilityId
import pro.liliya.core.authority.DirectAuthorityGrant
import pro.liliya.core.authority.ScopedGrantAuthorityPolicy
import pro.liliya.core.capability.CapabilityDescriptor
import pro.liliya.core.capability.CapabilityProviderId
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.license.JcaEcdsaP256LicenseSignatureVerifier
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseAuthorityComposition
import pro.liliya.core.license.LicenseAuthorityRequest
import pro.liliya.core.license.LicenseKeyId
import pro.liliya.core.license.LicensePolicyContext
import pro.liliya.core.license.LicensePolicyRequest
import pro.liliya.core.license.LicenseProductId
import pro.liliya.core.license.LicenseServiceOperation
import pro.liliya.core.license.LicenseServiceProtocolVersion
import pro.liliya.core.license.LicenseServiceRequestId
import pro.liliya.core.license.LicenseSubject
import pro.liliya.core.license.LicenseTrustedKeyResolver
import pro.liliya.core.license.LicenseTrustedVerificationKey
import pro.liliya.core.license.LicenseVerificationResult
import pro.liliya.core.license.LicenseVerifier
import pro.liliya.core.license.LicenseVersion
import pro.liliya.core.licensetransport.LicenseHttpTransportClient
import pro.liliya.core.licensetransport.LicenseHttpTransportConfig
import pro.liliya.core.licensetransport.LicenseServiceTransportRequest
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

/**
 * Physical-device overlay over the exact frozen RC plus the accepted live-Authority overlay.
 *
 * Proves the next boundary without starting model execution:
 * live signed License -> exact Authority admission -> StartupProvisioner ownership boundary.
 * An admitted request must reach the first downstream startup owner; an Authority-denied request
 * must not touch DEK, semantic, model, or prepared-input owners at all.
 */
@RunWith(AndroidJUnit4::class)
class PhysicalLiveStartupAdmissionGateInstrumentedTest {

    @Test
    fun live_admission_controls_downstream_startup_ownership_fail_closed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val application = instrumentation.targetContext.applicationContext as LiliyaApplication

        val endpoint = URL(requiredArgument(arguments.getString(ARG_ENDPOINT)))
        assertEquals("https", endpoint.protocol)

        val caBytes = Base64.getDecoder().decode(
            requiredArgument(arguments.getString(ARG_CA_BASE64))
        )
        try {
            installAcceptanceTrust(caBytes)
        } finally {
            caBytes.fill(0)
        }

        val encryptedStore = ProductionAndroidProductAuthEncryptedStore.create(application)
        val secretProbe = encryptedStore.openSecret()
        try {
            assertTrue(secretProbe.isNotEmpty())
        } finally {
            secretProbe.fill(0)
        }

        val acquisition = ProductionAndroidLicenseEnvelopeAcquisition.acquire(
            client = LicenseHttpTransportClient(
                LicenseHttpTransportConfig(
                    endpoint = endpoint,
                    connectTimeoutMillis = 5_000,
                    readTimeoutMillis = 10_000
                )
            ),
            request = LicenseServiceTransportRequest(
                protocolVersion = LicenseServiceProtocolVersion(1),
                operation = LicenseServiceOperation.ISSUE,
                productId = LicenseProductId(PRODUCT),
                subjectReference = LicenseSubject(SUBJECT),
                requestId = LicenseServiceRequestId(REQUEST_ID)
            ),
            authentication = ProductionAndroidProductAuthCredentialAdapter.bearerFactory(encryptedStore)
        )
        val signed = assertIs<ProductionAndroidLicenseEnvelopeAcquisitionResult.Signed>(acquisition)

        val publicKey = Base64.getDecoder().decode(
            requiredArgument(arguments.getString(ARG_ENTITLEMENT_KEY_BASE64))
        )
        val trusted = try {
            LicenseTrustedVerificationKey.of(
                keyId = signed.envelope.signingKeyId,
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
            ).verify(signed.envelope)
        )

        val logs = InMemoryLogWriter()
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "physical-live-startup-gate" }
        )

        val policyNow = verified.entitlement.notBefore
        val principal = AuthorityPrincipal(PRINCIPAL)
        val grantedCapability = CapabilityId(GRANTED_CAPABILITY)
        val deniedCapability = CapabilityId(DENIED_CAPABILITY)
        val scope = AuthorityScope.GLOBAL

        val capabilityAuthority = CapabilityAuthorityComposition(
            foundation = foundation,
            now = { policyNow }
        )
        assertIs<AndroidProductRuntimeStartupAuthorityAssemblyResult.Ready>(
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

        val activeGrants = capabilityAuthority.directGrantSnapshot().map { it.asScopedGrant() }
        assertEquals(1, activeGrants.size)

        val licenseAuthority = LicenseAuthorityComposition(
            foundation = foundation,
            authorityManager = AuthorityManager(
                policy = ScopedGrantAuthorityPolicy(
                    grants = activeGrants,
                    now = { policyNow }
                ),
                observability = foundation.observability
            )
        )

        val feature = verified.entitlement.features.first()
        val licenseRequest = LicensePolicyRequest(
            productId = verified.entitlement.productId,
            feature = feature,
            subject = verified.entitlement.subject
        )
        val policyContext = LicensePolicyContext(
            now = policyNow,
            minimumRevocationEpoch = verified.entitlement.revocationEpoch,
            minimumReplaySequence = verified.entitlement.replaySequence,
            suspiciousTimeOrReplayState = false
        )

        fun admission(capability: CapabilityId): AndroidProductRuntimeAdmissionResult =
            AndroidProductRuntimeAdmissionGate.admit(
                composition = licenseAuthority,
                verified = verified,
                licenseRequest = licenseRequest,
                policyContext = policyContext,
                authorityRequest = LicenseAuthorityRequest(
                    principal = principal,
                    capability = capability,
                    scope = scope
                )
            )

        assertIs<AndroidProductRuntimeAdmissionResult.Admitted>(admission(grantedCapability))
        val negativeAdmission = assertIs<AndroidProductRuntimeAdmissionResult.Rejected>(
            admission(deniedCapability)
        )
        assertEquals(AndroidProductRuntimeAdmissionFailure.AUTHORITY_DENIED, negativeAdmission.reason)

        val positiveActiveDekCalls = AtomicInteger(0)
        val positiveSemanticCalls = AtomicInteger(0)
        val positiveModelCalls = AtomicInteger(0)
        val positivePreparedCalls = AtomicInteger(0)

        val positiveProvisioning = AndroidProductRuntimeStartupProvisioner.prepare(
            AndroidProductRuntimeStartupProvisioningPorts(
                admission = AndroidProductRuntimeStartupAdmissionPort {
                    admission(grantedCapability)
                },
                activeDek = AndroidProductRuntimeStartupActiveDekPort {
                    positiveActiveDekCalls.incrementAndGet()
                    AndroidProductRuntimeStartupPreparationResult.Rejected
                },
                semantic = AndroidProductRuntimeStartupSemanticPort {
                    positiveSemanticCalls.incrementAndGet()
                    AndroidProductRuntimeStartupPreparationResult.Rejected
                },
                model = AndroidProductRuntimeStartupModelPort {
                    positiveModelCalls.incrementAndGet()
                    AndroidProductRuntimeStartupPreparationResult.Rejected
                },
                preparedInputs = AndroidProductRuntimeStartupPreparedInputsPort { _, _, _ ->
                    positivePreparedCalls.incrementAndGet()
                    AndroidProductRuntimeStartupPreparationResult.Rejected
                }
            )
        )
        val positiveStoppedAtFirstOwner = assertIs<AndroidProductRuntimeStartupProvisioningResult.Rejected>(
            positiveProvisioning
        )
        assertEquals(
            AndroidProductRuntimeStartupProvisioningFailure.ACTIVE_DEK_REJECTED,
            positiveStoppedAtFirstOwner.reason
        )
        assertEquals(1, positiveActiveDekCalls.get())
        assertEquals(0, positiveSemanticCalls.get())
        assertEquals(0, positiveModelCalls.get())
        assertEquals(0, positivePreparedCalls.get())

        val deniedActiveDekCalls = AtomicInteger(0)
        val deniedSemanticCalls = AtomicInteger(0)
        val deniedModelCalls = AtomicInteger(0)
        val deniedPreparedCalls = AtomicInteger(0)

        val deniedProvisioning = AndroidProductRuntimeStartupProvisioner.prepare(
            AndroidProductRuntimeStartupProvisioningPorts(
                admission = AndroidProductRuntimeStartupAdmissionPort {
                    admission(deniedCapability)
                },
                activeDek = AndroidProductRuntimeStartupActiveDekPort {
                    deniedActiveDekCalls.incrementAndGet()
                    AndroidProductRuntimeStartupPreparationResult.Rejected
                },
                semantic = AndroidProductRuntimeStartupSemanticPort {
                    deniedSemanticCalls.incrementAndGet()
                    AndroidProductRuntimeStartupPreparationResult.Rejected
                },
                model = AndroidProductRuntimeStartupModelPort {
                    deniedModelCalls.incrementAndGet()
                    AndroidProductRuntimeStartupPreparationResult.Rejected
                },
                preparedInputs = AndroidProductRuntimeStartupPreparedInputsPort { _, _, _ ->
                    deniedPreparedCalls.incrementAndGet()
                    AndroidProductRuntimeStartupPreparationResult.Rejected
                }
            )
        )
        val deniedAtGate = assertIs<AndroidProductRuntimeStartupProvisioningResult.AdmissionRejected>(
            deniedProvisioning
        )
        val rejected = assertIs<AndroidProductRuntimeAdmissionResult.Rejected>(deniedAtGate.result)
        assertEquals(AndroidProductRuntimeAdmissionFailure.AUTHORITY_DENIED, rejected.reason)
        assertEquals(0, deniedActiveDekCalls.get())
        assertEquals(0, deniedSemanticCalls.get())
        assertEquals(0, deniedModelCalls.get())
        assertEquals(0, deniedPreparedCalls.get())

        val replaySequence = verified.entitlement.replaySequence?.value ?: -1L
        println(
            "LILIYA_PHYSICAL_LIVE_STARTUP_GATE_EVIDENCE=" +
                "{\"https\":true," +
                "\"encryptedProductAuth\":true," +
                "\"liveLicense\":true," +
                "\"frozenCoreSignatureVerified\":true," +
                "\"authorityAdmission\":true," +
                "\"admittedReachedFirstStartupOwner\":true," +
                "\"authorityDeniedTouchedDownstream\":false," +
                "\"modelExecutionStarted\":false," +
                "\"replaySequence\":" + replaySequence + "}"
        )
        println("LILIYA_PHYSICAL_LIVE_STARTUP_GATE=PASS")
    }

    private fun installAcceptanceTrust(certificateBytes: ByteArray) {
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certificateBytes))
        val store = KeyStore.getInstance(KeyStore.getDefaultType())
        store.load(null)
        store.setCertificateEntry("liliya-local-acceptance-ca", certificate)

        val trustManagerFactory = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        )
        trustManagerFactory.init(store)

        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, trustManagerFactory.trustManagers, null)
        HttpsURLConnection.setDefaultSSLSocketFactory(ssl.socketFactory)
    }

    private fun requiredArgument(value: String?): String =
        value?.takeIf { it.isNotBlank() }
            ?: error("missing physical live-startup-gate acceptance argument")

    private companion object {
        const val ARG_ENDPOINT = "liveLicenseEndpoint"
        const val ARG_CA_BASE64 = "liveCaBase64"
        const val ARG_ENTITLEMENT_KEY_BASE64 = "liveEntitlementKeyBase64"

        const val PRODUCT = "liliya-pro"
        const val SUBJECT = "physical-android-product-auth-subject"
        const val REQUEST_ID = "physical-android-product-auth-startup-gate-001"
        const val ALGORITHM = "ECDSA-P256-SHA256"

        const val PRINCIPAL = "physical-liliya-app"
        const val GRANTED_CAPABILITY = "model.local"
        const val DENIED_CAPABILITY = "runtime.ungranted"
        const val PROVIDER = "physical-live-startup-gate-acceptance"
    }
}
