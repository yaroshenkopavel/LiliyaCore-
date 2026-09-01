package pro.liliya.core.license

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LicenseServiceStateVerificationContractTest {
    private val protocol = LicenseServiceProtocolVersion(1)
    private val purpose = LicenseServiceEvidencePurpose.SECURITY_STATE
    private val profile = LicenseServiceEvidenceProfile("TEST-SERVICE-SHA256")
    private val key = LicenseServiceTrustedVerificationKey.of(
        keyId = LicenseKeyId("service-state-key"),
        profile = profile,
        material = "PRIVATE-SERVICE-VERIFY-KEY".encodeToByteArray()
    )

    private fun state(
        replay: Long? = 11,
        revocation: Long? = 7,
        serverTime: Instant? = Instant.parse("2026-09-01T07:30:00Z")
    ) = LicenseServiceSecurityState(
        scope = LicenseServiceSecurityScope(
            productId = LicenseProductId("liliya-pro"),
            subject = LicenseSubject("PRIVATE-LICENSE-SUBJECT")
        ),
        revocationEpoch = revocation?.let(::LicenseRevocationEpoch),
        replaySequence = replay?.let(::LicenseReplaySequence),
        serverTime = serverTime
    )

    private fun signedEnvelope(
        state: LicenseServiceSecurityState = state(),
        signingKey: LicenseServiceTrustedVerificationKey = key,
        envelopeProfile: LicenseServiceEvidenceProfile = profile
    ): LicenseServiceStateEnvelope {
        val payload = LicenseServiceSecurityStateCanonicalCodec.encode(state)
        val unsigned = LicenseServiceStateEnvelope(
            protocolVersion = protocol,
            purpose = purpose,
            profile = envelopeProfile,
            signingKeyId = signingKey.keyId,
            payload = payload,
            proof = LicenseServiceAuthenticationProof.of(byteArrayOf(1))
        )
        return LicenseServiceStateEnvelope(
            protocolVersion = unsigned.protocolVersion,
            purpose = unsigned.purpose,
            profile = unsigned.profile,
            signingKeyId = unsigned.signingKeyId,
            payload = unsigned.payload,
            proof = LicenseServiceDigestTestProofVerifier.signForTest(signingKey, unsigned)
        )
    }

    private fun verifier(
        trustedKey: LicenseServiceTrustedVerificationKey? = key,
        supportedProfiles: Set<LicenseServiceEvidenceProfile> = setOf(profile),
        proofVerifier: LicenseServiceProofVerifier = LicenseServiceDigestTestProofVerifier
    ) = LicenseServiceStateVerifier(
        supportedProtocolVersion = protocol,
        supportedPurposes = setOf(purpose),
        supportedProfiles = supportedProfiles,
        trustedKeys = LicenseServiceTrustedKeyResolver { keyId, requestedProfile ->
            trustedKey?.takeIf {
                it.keyId == keyId && it.profile == requestedProfile
            }
        },
        proofVerifier = proofVerifier
    )

    @Test
    fun canonical_security_state_round_trips_exactly_and_redacts_subject() {
        val original = state()
        val payload = LicenseServiceSecurityStateCanonicalCodec.encode(original)
        val decoded = assertIs<LicenseServiceSecurityStateDecodeResult.Decoded>(
            LicenseServiceSecurityStateCanonicalCodec.decode(payload)
        ).state

        assertEquals(original, decoded)
        assertFalse("PRIVATE-LICENSE-SUBJECT" in original.toString())
        assertFalse("PRIVATE-LICENSE-SUBJECT" in payload.toString())
    }

    @Test
    fun verified_result_requires_supported_profile_exact_key_and_valid_proof() {
        val envelope = signedEnvelope()

        val result = assertIs<LicenseServiceStateVerificationResult.Verified>(
            verifier().verify(envelope)
        )

        assertEquals(state(), result.state)
        assertEquals(profile, result.envelope.profile)
        assertEquals(key.keyId, result.envelope.signingKeyId)
        assertFalse("PRIVATE-LICENSE-SUBJECT" in result.toString())
        assertFalse("PRIVATE-SERVICE-VERIFY-KEY" in result.toString())
    }

    @Test
    fun unsupported_profile_fails_before_key_or_proof_use() {
        var resolverCalls = 0
        var proofCalls = 0
        val unsupported = LicenseServiceEvidenceProfile("UNSUPPORTED")
        val envelope = LicenseServiceStateEnvelope(
            protocolVersion = protocol,
            purpose = purpose,
            profile = unsupported,
            signingKeyId = key.keyId,
            payload = LicenseServiceSecurityStateCanonicalCodec.encode(state()),
            proof = LicenseServiceAuthenticationProof.of(byteArrayOf(1))
        )
        val result = LicenseServiceStateVerifier(
            supportedProtocolVersion = protocol,
            supportedPurposes = setOf(purpose),
            supportedProfiles = setOf(profile),
            trustedKeys = LicenseServiceTrustedKeyResolver { _, _ ->
                resolverCalls++
                key
            },
            proofVerifier = LicenseServiceProofVerifier { _, _, _, _ ->
                proofCalls++
                true
            }
        ).verify(envelope)

        assertEquals(
            LicenseServiceStateVerificationRejection.UNSUPPORTED_PROFILE,
            assertIs<LicenseServiceStateVerificationResult.Rejected>(result).reason
        )
        assertEquals(0, resolverCalls)
        assertEquals(0, proofCalls)
    }

    @Test
    fun unknown_or_mismatched_trusted_key_fails_closed() {
        val envelope = signedEnvelope()
        val unknown = verifier(trustedKey = null).verify(envelope)
        assertEquals(
            LicenseServiceStateVerificationRejection.UNKNOWN_KEY_ID,
            assertIs<LicenseServiceStateVerificationResult.Rejected>(unknown).reason
        )

        val mismatched = LicenseServiceTrustedVerificationKey.of(
            keyId = LicenseKeyId("other-key"),
            profile = profile,
            material = byteArrayOf(9)
        )
        val mismatchResult = LicenseServiceStateVerifier(
            supportedProtocolVersion = protocol,
            supportedPurposes = setOf(purpose),
            supportedProfiles = setOf(profile),
            trustedKeys = LicenseServiceTrustedKeyResolver { _, _ -> mismatched },
            proofVerifier = LicenseServiceDigestTestProofVerifier
        ).verify(envelope)
        assertEquals(
            LicenseServiceStateVerificationRejection.TRUSTED_KEY_MISMATCH,
            assertIs<LicenseServiceStateVerificationResult.Rejected>(mismatchResult).reason
        )
    }

    @Test
    fun changing_authenticated_payload_without_new_proof_is_rejected() {
        val signed = signedEnvelope(state(replay = 11))
        val tampered = LicenseServiceStateEnvelope(
            protocolVersion = signed.protocolVersion,
            purpose = signed.purpose,
            profile = signed.profile,
            signingKeyId = signed.signingKeyId,
            payload = LicenseServiceSecurityStateCanonicalCodec.encode(state(replay = 12)),
            proof = signed.proof
        )

        val result = verifier().verify(tampered)
        assertEquals(
            LicenseServiceStateVerificationRejection.INVALID_PROOF,
            assertIs<LicenseServiceStateVerificationResult.Rejected>(result).reason
        )
    }

    @Test
    fun authenticated_noncanonical_payload_is_rejected_after_proof_verification() {
        val canonical = LicenseServiceSecurityStateCanonicalCodec.encode(state()).copyBytes()
        val productBytes = "liliya-pro".encodeToByteArray()
        val subjectStart = Int.SIZE_BYTES + Int.SIZE_BYTES + productBytes.size + Int.SIZE_BYTES
        canonical[subjectStart] = 0xC0.toByte()
        val noncanonicalPayload = LicenseServiceOpaquePayload.of(canonical)
        val unsigned = LicenseServiceStateEnvelope(
            protocolVersion = protocol,
            purpose = purpose,
            profile = profile,
            signingKeyId = key.keyId,
            payload = noncanonicalPayload,
            proof = LicenseServiceAuthenticationProof.of(byteArrayOf(1))
        )
        val signed = LicenseServiceStateEnvelope(
            protocolVersion = unsigned.protocolVersion,
            purpose = unsigned.purpose,
            profile = unsigned.profile,
            signingKeyId = unsigned.signingKeyId,
            payload = unsigned.payload,
            proof = LicenseServiceDigestTestProofVerifier.signForTest(key, unsigned)
        )

        val result = verifier().verify(signed)

        assertEquals(
            LicenseServiceStateVerificationRejection.INVALID_CANONICAL_PAYLOAD,
            assertIs<LicenseServiceStateVerificationResult.Rejected>(result).reason
        )
    }

    @Test
    fun malformed_payload_with_valid_proof_is_rejected_only_after_authentication() {
        var proofCalls = 0
        val malformedPayload = LicenseServiceOpaquePayload.of(
            "PRIVATE-MALFORMED-SERVICE-STATE".encodeToByteArray()
        )
        val unsigned = LicenseServiceStateEnvelope(
            protocolVersion = protocol,
            purpose = purpose,
            profile = profile,
            signingKeyId = key.keyId,
            payload = malformedPayload,
            proof = LicenseServiceAuthenticationProof.of(byteArrayOf(1))
        )
        val signed = LicenseServiceStateEnvelope(
            protocolVersion = unsigned.protocolVersion,
            purpose = unsigned.purpose,
            profile = unsigned.profile,
            signingKeyId = unsigned.signingKeyId,
            payload = unsigned.payload,
            proof = LicenseServiceDigestTestProofVerifier.signForTest(key, unsigned)
        )
        val result = verifier(
            proofVerifier = LicenseServiceProofVerifier { requestedProfile, trustedKey, transcript, proof ->
                proofCalls++
                LicenseServiceDigestTestProofVerifier.verify(
                    requestedProfile,
                    trustedKey,
                    transcript,
                    proof
                )
            }
        ).verify(signed)

        assertEquals(1, proofCalls)
        assertEquals(
            LicenseServiceStateVerificationRejection.INVALID_CANONICAL_PAYLOAD,
            assertIs<LicenseServiceStateVerificationResult.Rejected>(result).reason
        )
        val rendered = buildString {
            append(signed)
            append(signed.payload)
            append(signed.proof)
            append(result)
        }
        assertFalse("PRIVATE-MALFORMED-SERVICE-STATE" in rendered)
        assertFalse("PRIVATE-SERVICE-VERIFY-KEY" in rendered)
        assertTrue("<redacted>" in rendered)
    }
}
