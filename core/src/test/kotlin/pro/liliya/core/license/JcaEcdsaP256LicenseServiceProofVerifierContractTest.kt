package pro.liliya.core.license

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JcaEcdsaP256LicenseServiceProofVerifierContractTest {
    @Test
    fun valid_p256_service_state_proof_is_verified() {
        val keys = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val profile = LicenseServiceEvidenceProfile(
            "ECDSA-P256-SHA256-SERVICE-STATE-V1"
        )
        val keyId = LicenseKeyId("service-state-key-v1")
        val envelopeWithoutProof = LicenseServiceStateEnvelope(
            protocolVersion = LicenseServiceProtocolVersion(1),
            purpose = LicenseServiceEvidencePurpose.SECURITY_STATE,
            profile = profile,
            signingKeyId = keyId,
            payload = LicenseServiceSecurityStateCanonicalCodec.encode(
                LicenseServiceSecurityState(
                    scope = LicenseServiceSecurityScope(
                        LicenseProductId("liliya-pro"),
                        LicenseSubject("subject-1")
                    ),
                    revocationEpoch = LicenseRevocationEpoch(4)
                )
            ),
            proof = LicenseServiceAuthenticationProof.of(byteArrayOf(1))
        )
        val transcript = LicenseServiceAuthenticationTranscript.from(envelopeWithoutProof)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private)
            update(transcript.copyBytes())
            sign()
        }
        val trusted = LicenseServiceTrustedVerificationKey.of(
            keyId = keyId,
            profile = profile,
            material = keys.public.encoded
        )

        assertTrue(
            JcaEcdsaP256LicenseServiceProofVerifier.verify(
                profile,
                trusted,
                transcript,
                LicenseServiceAuthenticationProof.of(signature)
            )
        )
    }

    @Test
    fun altered_transcript_is_rejected() {
        val keys = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val profile = LicenseServiceEvidenceProfile(
            "ECDSA-P256-SHA256-SERVICE-STATE-V1"
        )
        val keyId = LicenseKeyId("service-state-key-v1")
        fun envelope(epoch: Long) = LicenseServiceStateEnvelope(
            LicenseServiceProtocolVersion(1),
            LicenseServiceEvidencePurpose.SECURITY_STATE,
            profile,
            keyId,
            LicenseServiceSecurityStateCanonicalCodec.encode(
                LicenseServiceSecurityState(
                    LicenseServiceSecurityScope(
                        LicenseProductId("liliya-pro"),
                        LicenseSubject("subject-1")
                    ),
                    revocationEpoch = LicenseRevocationEpoch(epoch)
                )
            ),
            LicenseServiceAuthenticationProof.of(byteArrayOf(1))
        )
        val signedTranscript = LicenseServiceAuthenticationTranscript.from(envelope(1))
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private)
            update(signedTranscript.copyBytes())
            sign()
        }
        val altered = LicenseServiceAuthenticationTranscript.from(envelope(2))
        val trusted = LicenseServiceTrustedVerificationKey.of(
            keyId, profile, keys.public.encoded
        )

        assertFalse(
            JcaEcdsaP256LicenseServiceProofVerifier.verify(
                profile,
                trusted,
                altered,
                LicenseServiceAuthenticationProof.of(signature)
            )
        )
    }
}
