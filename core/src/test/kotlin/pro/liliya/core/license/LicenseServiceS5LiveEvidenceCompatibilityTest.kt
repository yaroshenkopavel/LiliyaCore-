package pro.liliya.core.license

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Assumptions.assumeTrue

class LicenseServiceS5LiveEvidenceCompatibilityTest {
    @Test
    fun backend_live_kms_evidence_is_accepted_by_frozen_license_verifier() {
        val evidencePath = System.getenv("LIVE_LICENSE_EVIDENCE_PATH")
            ?.takeIf { it.isNotBlank() }
        assumeTrue(evidencePath != null, "LIVE_LICENSE_EVIDENCE_PATH is not configured")

        val path = Path.of(evidencePath!!)
        assumeTrue(Files.isRegularFile(path), "live License evidence file is missing")

        val properties = Properties().apply {
            Files.newInputStream(path).use(::load)
        }

        val schemaVersion = LicenseVersion(
            required(properties, "schemaVersion").toLong()
        )
        val algorithm = LicenseAlgorithm(
            required(properties, "algorithm")
        )
        val keyId = LicenseKeyId(
            required(properties, "keyReference")
        )
        val payload = LicenseCanonicalPayload.of(
            Base64.getDecoder().decode(required(properties, "payloadBase64"))
        )
        val signature = LicenseSignature.of(
            Base64.getDecoder().decode(required(properties, "signatureBase64"))
        )
        val publicKeyDer = Base64.getDecoder().decode(
            required(properties, "publicKeyDerBase64")
        )

        val envelope = LicenseSignedEnvelope(
            schemaVersion = schemaVersion,
            algorithm = algorithm,
            signingKeyId = keyId,
            payload = payload,
            signature = signature
        )
        val trustedKey = LicenseTrustedVerificationKey.of(
            keyId = keyId,
            algorithm = algorithm,
            material = publicKeyDer
        )

        val verified = assertIs<LicenseVerificationResult.Verified>(
            LicenseVerifier(
                supportedSchemaVersion = LicenseVersion(1),
                supportedAlgorithms = setOf(LicenseAlgorithm("ECDSA-P256-SHA256")),
                trustedKeys = LicenseTrustedKeyResolver { requested ->
                    trustedKey.takeIf { requested == keyId }
                },
                signatureVerifier = JcaEcdsaP256LicenseSignatureVerifier
            ).verify(envelope)
        )

        assertEquals(keyId, verified.entitlement.signingKeyId)
        assertEquals("liliya-pro", verified.entitlement.productId.value)
        assertEquals(setOf("model.local"), verified.entitlement.features.map { it.value }.toSet())
        assertEquals(LicenseReplaySequence(11), verified.entitlement.replaySequence)
        assertEquals(LicenseRevocationEpoch(7), verified.entitlement.revocationEpoch)
    }

    private fun required(properties: Properties, key: String): String =
        properties.getProperty(key)
            ?.takeIf { it.isNotBlank() }
            ?: error("missing live License evidence property: " + key)
}
