package pro.liliya.core.license

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JcaEcdsaP256LicenseSignatureVerifierContractTest {
    @Test
    fun valid_p256_sha256_signature_is_accepted() {
        val fixture = fixture("license-payload")

        assertTrue(
            JcaEcdsaP256LicenseSignatureVerifier.verify(
                algorithm = fixture.algorithm,
                key = fixture.trustedKey,
                payload = fixture.payload,
                signature = fixture.signature
            )
        )
    }

    @Test
    fun one_byte_payload_tamper_is_rejected() {
        val fixture = fixture("license-payload")
        val tampered = LicenseCanonicalPayload.of(
            fixture.payload.copyBytes().also {
                it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            }
        )

        assertFalse(
            JcaEcdsaP256LicenseSignatureVerifier.verify(
                algorithm = fixture.algorithm,
                key = fixture.trustedKey,
                payload = tampered,
                signature = fixture.signature
            )
        )
    }

    @Test
    fun unsupported_algorithm_is_rejected() {
        val fixture = fixture("license-payload")

        assertFalse(
            JcaEcdsaP256LicenseSignatureVerifier.verify(
                algorithm = LicenseAlgorithm("TEST-SHA256"),
                key = fixture.trustedKey,
                payload = fixture.payload,
                signature = fixture.signature
            )
        )
    }

    @Test
    fun wrong_public_key_is_rejected() {
        val fixture = fixture("license-payload")
        val other = keyPair()
        val wrongTrustedKey = LicenseTrustedVerificationKey.of(
            keyId = fixture.trustedKey.keyId,
            algorithm = fixture.algorithm,
            material = other.public.encoded
        )

        assertFalse(
            JcaEcdsaP256LicenseSignatureVerifier.verify(
                algorithm = fixture.algorithm,
                key = wrongTrustedKey,
                payload = fixture.payload,
                signature = fixture.signature
            )
        )
    }

    @Test
    fun malformed_public_key_material_fails_closed() {
        val fixture = fixture("license-payload")
        val malformed = LicenseTrustedVerificationKey.of(
            keyId = fixture.trustedKey.keyId,
            algorithm = fixture.algorithm,
            material = byteArrayOf(1, 2, 3, 4)
        )

        assertFalse(
            JcaEcdsaP256LicenseSignatureVerifier.verify(
                algorithm = fixture.algorithm,
                key = malformed,
                payload = fixture.payload,
                signature = fixture.signature
            )
        )
    }

    private fun fixture(text: String): Fixture {
        val algorithm = LicenseAlgorithm("ECDSA-P256-SHA256")
        val pair = keyPair()
        val payload = LicenseCanonicalPayload.of(text.encodeToByteArray())
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(pair.private)
        signer.update(payload.copyBytes())
        val signature = LicenseSignature.of(signer.sign())
        val trusted = LicenseTrustedVerificationKey.of(
            keyId = LicenseKeyId("test-ecdsa-p256-v1"),
            algorithm = algorithm,
            material = pair.public.encoded
        )
        return Fixture(algorithm, trusted, payload, signature)
    }

    private fun keyPair() =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    private data class Fixture(
        val algorithm: LicenseAlgorithm,
        val trustedKey: LicenseTrustedVerificationKey,
        val payload: LicenseCanonicalPayload,
        val signature: LicenseSignature
    )
}
