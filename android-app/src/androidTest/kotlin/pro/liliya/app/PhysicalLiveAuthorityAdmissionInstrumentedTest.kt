package pro.liliya.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Base64
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
import pro.liliya.android.runtime.AndroidProductRuntimeStartupAuthorityAssemblyResult
import pro.liliya.android.runtime.AndroidProductRuntimeStartupAuthorityGrantAssembly
import pro.liliya.android.runtime.AndroidProductRuntimeStartupAuthorityPlan
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
 * Physical-device acceptance overlay over the exact frozen RC.
 *
 * Proves, without starting model/runtime execution:
 * encrypted Product Auth -> live HTTPS Licensing Service -> signed License -> frozen Core verify
 * -> exact caller-approved startup Authority plan -> License+Authority Admission positive path
 * -> fail-closed Authority denial for an ungranted capability.
 */
@RunWith(AndroidJUnit4::class)
class PhysicalLiveAuthorityAdmissionInstrumentedTest {

    @Test
    fun live_signed_license_enters_authority_admission_and_denies_ungranted_capability() {
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

        val client = LicenseHttpTransportClient(
            LicenseHttpTransportConfig(
                endpoint = endpoint,
                connectTimeoutMillis = 5_000,
                readTimeoutMillis = 10_000
            )
        )

        val acquisition = ProductionAndroidLicenseEnvelopeAcquisition.acquire(
            client = client,
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

        assertEquals(LicenseProductId(PRODUCT), verified.entitlement.productId)
        assertEquals(LicenseSubject(SUBJECT), verified.entitlement.subject)
        assertTrue(verified.entitlement.features.isNotEmpty())

        val logs = InMemoryLogWriter()
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "physical-live-authority" }
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

        val authorityInstall = AndroidProductRuntimeStartupAuthorityGrantAssembly.install(
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
        assertIs<AndroidProductRuntimeStartupAuthorityAssemblyResult.Ready>(authorityInstall)

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

        val positive = AndroidProductRuntimeAdmissionGate.admit(
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
        assertIs<AndroidProductRuntimeAdmissionResult.Admitted>(positive)

        val negative = AndroidProductRuntimeAdmissionGate.admit(
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
        val rejected = assertIs<AndroidProductRuntimeAdmissionResult.Rejected>(negative)
        assertEquals(AndroidProductRuntimeAdmissionFailure.AUTHORITY_DENIED, rejected.reason)

        val replaySequence = verified.entitlement.replaySequence?.value ?: -1L
        println(
            "LILIYA_PHYSICAL_LIVE_AUTHORITY_EVIDENCE=" +
                "{\"https\":true," +
                "\"encryptedProductAuth\":true," +
                "\"liveLicense\":true," +
                "\"frozenCoreSignatureVerified\":true," +
                "\"startupAuthorityPlanInstalled\":true," +
                "\"positiveAdmission\":true," +
                "\"negativeAuthorityDeny\":true," +
                "\"replaySequence\":" + replaySequence + "," +
                "\"authorityExecutionStarted\":false}"
        )
        println("LILIYA_PHYSICAL_LIVE_AUTHORITY_ADMISSION=PASS")
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
            ?: error("missing physical live-authority acceptance argument")

    private companion object {
        const val ARG_ENDPOINT = "liveLicenseEndpoint"
        const val ARG_CA_BASE64 = "liveCaBase64"
        const val ARG_ENTITLEMENT_KEY_BASE64 = "liveEntitlementKeyBase64"

        const val PRODUCT = "liliya-pro"
        const val SUBJECT = "physical-android-product-auth-subject"
        const val REQUEST_ID = "physical-android-product-auth-authority-001"
        const val ALGORITHM = "ECDSA-P256-SHA256"

        const val PRINCIPAL = "physical-liliya-app"
        const val GRANTED_CAPABILITY = "model.local"
        const val DENIED_CAPABILITY = "runtime.ungranted"
        const val PROVIDER = "physical-live-authority-acceptance"
    }
}
