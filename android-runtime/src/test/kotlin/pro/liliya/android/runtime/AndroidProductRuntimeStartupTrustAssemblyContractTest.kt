package pro.liliya.android.runtime

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import pro.liliya.core.authority.CapabilityAuthorityComposition
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.license.LicenseVerificationRejection
import pro.liliya.core.license.LicenseVerificationResult
import pro.liliya.core.logging.LoggerFactory
import pro.liliya.core.observability.LoggerProvider

class AndroidProductRuntimeStartupTrustAssemblyContractTest {
    @Test
    fun production_jca_signature_verification_creates_exact_trust_ownership() {
        val algorithm = pro.liliya.core.license.LicenseAlgorithm("ECDSA-P256-SHA256")
        val schemaVersion = pro.liliya.core.license.LicenseVersion(1)
        val keyId = pro.liliya.core.license.LicenseKeyId("startup-trust-key")
        val entitlement = pro.liliya.core.license.LicenseEntitlement(
            id = pro.liliya.core.license.LicenseId("startup-license"),
            subject = pro.liliya.core.license.LicenseSubject("startup-subject"),
            productId = pro.liliya.core.license.LicenseProductId("liliya-core"),
            features = setOf(pro.liliya.core.license.LicenseFeature("runtime")),
            version = schemaVersion,
            signingKeyId = keyId,
            issuedAt = Instant.parse("2026-09-09T00:00:00Z"),
            notBefore = Instant.parse("2026-09-09T00:00:00Z"),
            expiresAt = Instant.parse("2027-09-09T00:00:00Z"),
            offlineLeaseUntil = Instant.parse("2026-10-09T00:00:00Z"),
            revocationEpoch = pro.liliya.core.license.LicenseRevocationEpoch(0),
            replaySequence = pro.liliya.core.license.LicenseReplaySequence(1)
        )
        val payload = pro.liliya.core.license.LicenseEntitlementCanonicalCodec.encode(entitlement)
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val signatureBytes = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(payload.copyBytes())
            sign()
        }
        val envelope = pro.liliya.core.license.LicenseSignedEnvelope(
            schemaVersion = schemaVersion,
            algorithm = algorithm,
            signingKeyId = keyId,
            payload = payload,
            signature = pro.liliya.core.license.LicenseSignature.of(signatureBytes)
        )
        val trustedKey = pro.liliya.core.license.LicenseTrustedVerificationKey.of(
            keyId = keyId,
            algorithm = algorithm,
            material = keyPair.public.encoded
        )

        val result = AndroidProductRuntimeStartupTrustAssembly.create(
            AndroidProductRuntimeStartupTrustInput(
                diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
                loggerProvider = LoggerProvider(LoggerFactory::create),
                supportedLicenseSchemaVersion = schemaVersion,
                trustedKeys = pro.liliya.core.license.LicenseTrustedKeyResolver { requested ->
                    trustedKey.takeIf { requested == keyId }
                },
                licenseEnvelope = envelope
            )
        )

        val ready = assertIs<AndroidProductRuntimeStartupTrustAssemblyResult.Ready>(result)
        assertEquals(entitlement, ready.ownership.verifiedLicense.entitlement)
        assertEquals(envelope, ready.ownership.verifiedLicense.envelope)
    }

    @Test
    fun verification_rejection_is_preserved_without_authority_minting() {
        val foundation = foundation()
        val authority = CapabilityAuthorityComposition(foundation)

        val result = AndroidProductRuntimeStartupTrustAssembly.resolve(
            foundation = foundation,
            authority = authority,
            verificationPort = AndroidProductRuntimeStartupTrustVerificationPort {
                LicenseVerificationResult.Rejected(
                    LicenseVerificationRejection.INVALID_SIGNATURE
                )
            }
        )

        val rejected = assertIs<AndroidProductRuntimeStartupTrustAssemblyResult.VerificationRejected>(result)
        assertEquals(LicenseVerificationRejection.INVALID_SIGNATURE, rejected.reason)
    }

    @Test
    fun verification_exception_is_bounded() {
        val foundation = foundation()
        val authority = CapabilityAuthorityComposition(foundation)

        val result = AndroidProductRuntimeStartupTrustAssembly.resolve(
            foundation = foundation,
            authority = authority,
            verificationPort = AndroidProductRuntimeStartupTrustVerificationPort {
                error("private trust failure")
            }
        )

        assertIs<AndroidProductRuntimeStartupTrustAssemblyResult.Failed>(result)
    }

    private fun foundation(): FoundationComposition {
        val diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink())
        return FoundationComposition(
            diagnostics = diagnostics,
            loggerProvider = LoggerProvider(LoggerFactory::create)
        )
    }
}
