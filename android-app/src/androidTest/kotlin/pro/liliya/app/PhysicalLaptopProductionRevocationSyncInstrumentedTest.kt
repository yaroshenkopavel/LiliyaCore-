package pro.liliya.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.net.URL
import java.time.Instant
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.license.JcaEcdsaP256LicenseSignatureVerifier
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseCanonicalPayload
import pro.liliya.core.license.LicenseDecision
import pro.liliya.core.license.LicenseDenialReason
import pro.liliya.core.license.LicenseKeyId
import pro.liliya.core.license.LicensePolicy
import pro.liliya.core.license.LicensePolicyContext
import pro.liliya.core.license.LicensePolicyRequest
import pro.liliya.core.license.LicenseServiceEvidenceProfile
import pro.liliya.core.license.LicenseServiceTrustedVerificationKey
import pro.liliya.core.license.LicenseSignature
import pro.liliya.core.license.LicenseSignedEnvelope
import pro.liliya.core.license.LicenseTrustedKeyResolver
import pro.liliya.core.license.LicenseTrustedVerificationKey
import pro.liliya.core.license.LicenseVerificationResult
import pro.liliya.core.license.LicenseVerifier
import pro.liliya.core.license.LicenseVersion
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.licensetransport.LicenseHttpTlsTrust
import pro.liliya.core.licensetransport.LicenseHttpTransportConfig

@RunWith(AndroidJUnit4::class)
class PhysicalLaptopProductionRevocationSyncInstrumentedTest {
    @After
    fun cleanupOwner() {
        ProductionAndroidLicenseServiceSecuritySyncProfileSourceOwner.clearForTests()
    }

    @Test
    fun online_sync_persists_revocation_floor_and_offline_restart_restores_it() {
        val args = InstrumentationRegistry.getArguments()
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as LiliyaApplication
        val endpoint = URL(required(args.getString(ARG_ENDPOINT)))
        assertEquals("https", endpoint.protocol)

        val baseline = File(app.filesDir, BASELINE_FILE)
        val oldVerified = verifyLicense(
            envelope = readEnvelope(baseline),
            keyFile = File(app.filesDir, LICENSE_KEY_FILE)
        )
        val oldEntitlement = oldVerified.entitlement

        val caBytes = File(app.filesDir, CA_FILE).readBytes()
        val tlsTrust = try {
            LicenseHttpTlsTrust.ofCertificate(caBytes)
        } finally {
            caBytes.fill(0)
        }

        val serviceKeyBytes = File(app.filesDir, SERVICE_STATE_KEY_FILE).readBytes()
        val trustedServiceKey = try {
            LicenseServiceTrustedVerificationKey.of(
                keyId = LicenseKeyId(SERVICE_STATE_KEY_ID),
                profile = LicenseServiceEvidenceProfile(SERVICE_PROFILE),
                material = serviceKeyBytes
            )
        } finally {
            serviceKeyBytes.fill(0)
        }

        ProductionAndroidLicenseServiceSecuritySyncProfileSourceOwner.clearForTests()
        assertTrue(
            ProductionAndroidLicenseServiceSecuritySyncProfileSourceOwner.install(
                ProductionAndroidLicenseServiceSecuritySyncProfileSource {
                    ProductionAndroidLicenseServiceSecuritySyncProfile(
                        transport = LicenseHttpTransportConfig(
                            endpoint = endpoint,
                            connectTimeoutMillis = 10_000,
                            readTimeoutMillis = 60_000,
                            developmentAllowInsecureHttp = false,
                            tlsTrust = tlsTrust
                        ),
                        trustedServiceStateKey = trustedServiceKey
                    )
                }
            )
        )

        val online = assertIs<ProductionAndroidLicenseServiceSecuritySyncCoreResult.Ready>(
            ProductionAndroidLicenseServiceSecuritySync.refreshCore(
                context = app,
                foundation = foundation("online"),
                verifiedLicense = oldVerified,
                currentPolicyContext = baselineContext(oldVerified)
            )
        )
        assertEquals(
            ProductionAndroidLicenseServiceSecuritySyncContact.VERIFIED_ADVANCED,
            online.contact
        )
        assertTrue(
            online.context.minimumRevocationEpoch.value >
                oldEntitlement.revocationEpoch.value
        )
        assertStale(oldVerified, online.context)

        val persistedFloor = online.context.minimumRevocationEpoch
        val persistedReplay = online.context.minimumReplaySequence

        ProductionAndroidLicenseServiceSecuritySyncProfileSourceOwner.clearForTests()

        val offline = assertIs<ProductionAndroidLicenseServiceSecuritySyncCoreResult.Ready>(
            ProductionAndroidLicenseServiceSecuritySync.refreshCore(
                context = app,
                foundation = foundation("offline-restart"),
                verifiedLicense = oldVerified,
                currentPolicyContext = baselineContext(oldVerified)
            )
        )
        assertEquals(
            ProductionAndroidLicenseServiceSecuritySyncContact.NOT_CONFIGURED,
            offline.contact
        )
        assertEquals(
            persistedFloor.value,
            offline.context.minimumRevocationEpoch.value
        )
        assertEquals(
            persistedReplay?.value,
            offline.context.minimumReplaySequence?.value
        )
        assertStale(oldVerified, offline.context)

        assertTrue(baseline.delete())
        assertTrue(!baseline.exists())

        println(
            "LILIYA_PRODUCTION_REVOCATION_SYNC=" +
                "{\"onlineVerifiedAdvanced\":true," +
                "\"durableFloorPersisted\":true," +
                "\"offlineRestoreWithoutContact\":true," +
                "\"oldLicenseDeniedOnline\":true," +
                "\"oldLicenseDeniedOffline\":true," +
                "\"denial\":\"STALE_REVOCATION_EPOCH\"," +
                "\"authorityExecutionStarted\":false}"
        )
    }

    private fun baselineContext(
        verified: LicenseVerificationResult.Verified
    ): LicensePolicyContext =
        LicensePolicyContext(
            now = Instant.now(),
            minimumRevocationEpoch = verified.entitlement.revocationEpoch,
            requiredDeviceBindingReference = verified.entitlement.deviceBindingReference
        )

    private fun assertStale(
        verified: LicenseVerificationResult.Verified,
        context: LicensePolicyContext
    ) {
        val entitlement = verified.entitlement
        val decision = LicensePolicy().evaluate(
            verified = verified,
            request = LicensePolicyRequest(
                productId = entitlement.productId,
                feature = entitlement.features.first(),
                subject = entitlement.subject
            ),
            context = LicensePolicyContext(
                now = Instant.now(),
                minimumRevocationEpoch = context.minimumRevocationEpoch,
                minimumReplaySequence = context.minimumReplaySequence,
                suspiciousTimeOrReplayState = context.suspiciousTimeOrReplayState,
                requiredDeviceBindingReference = entitlement.deviceBindingReference
            )
        )
        val denied = assertIs<LicenseDecision.Denied>(decision)
        assertEquals(LicenseDenialReason.STALE_REVOCATION_EPOCH, denied.reason)
    }

    private fun foundation(label: String): FoundationComposition {
        val logs = InMemoryLogWriter()
        val diagnostics = InMemoryDiagnosticSink()
        var nextId = 0
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(diagnostics),
            loggerProvider = LoggerProvider { context ->
                StructuredLogger(context, logs)
            },
            correlationIds = CorrelationIdGenerator {
                "physical-revocation-sync-" + label + "-" + (++nextId)
            }
        )
    }

    private fun verifyLicense(
        envelope: LicenseSignedEnvelope,
        keyFile: File
    ): LicenseVerificationResult.Verified {
        val publicKey = Base64.getDecoder().decode(
            keyFile.readLines()
                .filterNot { it.startsWith("-----") }
                .joinToString("")
                .trim()
        )
        val trusted = try {
            LicenseTrustedVerificationKey.of(
                keyId = envelope.signingKeyId,
                algorithm = LicenseAlgorithm(ALGORITHM),
                material = publicKey
            )
        } finally {
            publicKey.fill(0)
        }

        return assertIs(
            LicenseVerifier(
                supportedSchemaVersion = LicenseVersion(1),
                supportedAlgorithms = setOf(LicenseAlgorithm(ALGORITHM)),
                trustedKeys = LicenseTrustedKeyResolver { requested ->
                    trusted.takeIf { it.keyId == requested }
                },
                signatureVerifier = JcaEcdsaP256LicenseSignatureVerifier
            ).verify(envelope)
        )
    }

    private fun readEnvelope(file: File): LicenseSignedEnvelope {
        assertTrue(file.isFile)
        return DataInputStream(FileInputStream(file)).use { input ->
            assertEquals(MAGIC, input.readInt())
            val schema = LicenseVersion(input.readLong())
            val algorithm = LicenseAlgorithm(input.readUTF())
            val keyId = LicenseKeyId(input.readUTF())
            val payload = ByteArray(input.readInt()).also(input::readFully)
            val signature = ByteArray(input.readInt()).also(input::readFully)
            LicenseSignedEnvelope(
                schemaVersion = schema,
                algorithm = algorithm,
                signingKeyId = keyId,
                payload = LicenseCanonicalPayload.of(payload),
                signature = LicenseSignature.of(signature)
            ).also {
                payload.fill(0)
                signature.fill(0)
            }
        }
    }

    private fun required(value: String?): String =
        value?.takeIf { it.isNotBlank() }
            ?: error("missing production revocation sync argument")

    private companion object {
        const val ARG_ENDPOINT = "revocationEndpoint"
        const val CA_FILE = "licensing-ca.crt"
        const val LICENSE_KEY_FILE = "license-signing-v1-public.pem"
        const val SERVICE_STATE_KEY_FILE = "license-service-state-v1-public.der"
        const val BASELINE_FILE = "physical-revocation-baseline.bin"
        const val ALGORITHM = "ECDSA-P256-SHA256"
        const val SERVICE_PROFILE = "ECDSA-P256-SHA256-SERVICE-STATE-V1"
        const val SERVICE_STATE_KEY_ID = "liliya-prod-service-state-v1"
        const val MAGIC = 0x4C525631
    }
}
