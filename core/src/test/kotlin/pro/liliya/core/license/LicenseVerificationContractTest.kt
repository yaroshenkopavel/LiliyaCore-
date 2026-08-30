package pro.liliya.core.license

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class LicenseVerificationContractTest {
    private val algorithm = LicenseAlgorithm("TEST-SHA256")
    private val schemaVersion = LicenseVersion(1)
    private val trustedKey = LicenseTrustedVerificationKey.of(
        keyId = LicenseKeyId("trusted-key-1"),
        algorithm = algorithm,
        material = "private-test-key-material".toByteArray(StandardCharsets.UTF_8)
    )
    private val issuedAt = Instant.parse("2026-08-30T17:30:00Z")

    private fun foundation(): FoundationComposition {
        val sequence = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, InMemoryLogWriter()) },
            correlationIds = CorrelationIdGenerator { "license-verification-${sequence.incrementAndGet()}" }
        )
    }

    private fun entitlement(
        signingKeyId: LicenseKeyId = trustedKey.keyId,
        features: Set<LicenseFeature> = linkedSetOf(
            LicenseFeature("memory.protected"),
            LicenseFeature("model.local")
        )
    ) = LicenseEntitlement(
        id = LicenseId("license-verified-1"),
        subject = LicenseSubject("private-subject-reference"),
        productId = LicenseProductId("liliya-pro"),
        features = features,
        version = LicenseVersion(7),
        signingKeyId = signingKeyId,
        issuedAt = issuedAt,
        notBefore = issuedAt.minusSeconds(30),
        expiresAt = issuedAt.plusSeconds(86_400),
        offlineLeaseUntil = issuedAt.plusSeconds(43_200),
        revocationEpoch = LicenseRevocationEpoch(4),
        replaySequence = LicenseReplaySequence(19)
    )

    private fun composition(
        resolver: LicenseTrustedKeyResolver = LicenseTrustedKeyResolver { keyId ->
            if (keyId == trustedKey.keyId) trustedKey else null
        },
        algorithms: Set<LicenseAlgorithm> = setOf(algorithm)
    ) = LicenseVerificationComposition(
        foundation = foundation(),
        supportedSchemaVersion = schemaVersion,
        supportedAlgorithms = algorithms,
        trustedKeys = resolver,
        signatureVerifier = LicenseDigestTestVerifier
    )

    private fun envelope(
        entitlement: LicenseEntitlement = entitlement(),
        keyId: LicenseKeyId = trustedKey.keyId,
        algorithm: LicenseAlgorithm = this.algorithm,
        schemaVersion: LicenseVersion = this.schemaVersion,
        corruptSignature: Boolean = false,
        payloadOverride: LicenseCanonicalPayload? = null
    ): LicenseSignedEnvelope {
        val payload = payloadOverride ?: LicenseEntitlementCanonicalCodec.encode(entitlement)
        val signature = if (corruptSignature) {
            LicenseSignature.of(byteArrayOf(1, 2, 3, 4))
        } else {
            LicenseDigestTestVerifier.signForTest(trustedKey, payload)
        }
        return LicenseSignedEnvelope(
            schemaVersion = schemaVersion,
            algorithm = algorithm,
            signingKeyId = keyId,
            payload = payload,
            signature = signature
        )
    }

    @Test
    fun canonical_codec_round_trips_exact_entitlement_and_normalizes_feature_order() {
        val first = entitlement(
            features = linkedSetOf(
                LicenseFeature("memory.protected"),
                LicenseFeature("model.local")
            )
        )
        val second = entitlement(
            features = linkedSetOf(
                LicenseFeature("model.local"),
                LicenseFeature("memory.protected")
            )
        )

        val encodedFirst = LicenseEntitlementCanonicalCodec.encode(first)
        val encodedSecond = LicenseEntitlementCanonicalCodec.encode(second)
        assertContentEquals(encodedFirst.copyBytes(), encodedSecond.copyBytes())
        val decoded = assertIs<LicenseEntitlementDecodeResult.Decoded>(
            LicenseEntitlementCanonicalCodec.decode(encodedFirst)
        )
        assertEquals(first, decoded.entitlement)
    }

    @Test
    fun canonical_payload_signature_and_key_material_are_defensively_copied_and_redacted() {
        val payloadBytes = "private entitlement body".toByteArray(StandardCharsets.UTF_8)
        val payload = LicenseCanonicalPayload.of(payloadBytes)
        payloadBytes.fill(0)
        assertEquals("private entitlement body", String(payload.copyBytes(), StandardCharsets.UTF_8))

        val keyBytes = "secret-key-material".toByteArray(StandardCharsets.UTF_8)
        val key = LicenseTrustedVerificationKey.of(trustedKey.keyId, algorithm, keyBytes)
        keyBytes.fill(0)
        assertEquals("secret-key-material", String(key.copyMaterial(), StandardCharsets.UTF_8))

        val signatureBytes = byteArrayOf(9, 8, 7)
        val signature = LicenseSignature.of(signatureBytes)
        signatureBytes.fill(0)
        assertEquals(listOf<Byte>(9, 8, 7), signature.copyBytes().toList())

        assertFalse(payload.toString().contains("private entitlement body"))
        assertFalse(key.toString().contains("secret-key-material"))
        assertFalse(signature.toString().contains("9"))
    }

    @Test
    fun exact_trusted_key_id_supported_algorithm_and_valid_canonical_payload_produce_verified_entitlement() {
        val expected = entitlement()
        val result = assertIs<LicenseVerificationResult.Verified>(
            composition().verify(envelope(entitlement = expected))
        )
        assertEquals(expected, result.entitlement)
    }

    @Test
    fun unknown_key_id_fails_closed_before_signature_verification_can_grant() {
        var signatureCalls = 0
        val verifier = LicenseVerifier(
            supportedSchemaVersion = schemaVersion,
            supportedAlgorithms = setOf(algorithm),
            trustedKeys = LicenseTrustedKeyResolver { null },
            signatureVerifier = LicenseSignatureVerifier { _, _, _, _ ->
                signatureCalls++
                true
            }
        )

        val result = verifier.verify(envelope(keyId = LicenseKeyId("attacker-selected-key")))
        assertEquals(
            LicenseVerificationRejection.UNKNOWN_KEY_ID,
            assertIs<LicenseVerificationResult.Rejected>(result).reason
        )
        assertEquals(0, signatureCalls)
    }

    @Test
    fun unsupported_algorithm_fails_closed_before_trusted_key_resolution() {
        var resolverCalls = 0
        val unsupported = LicenseAlgorithm("NONE")
        val verifier = LicenseVerifier(
            supportedSchemaVersion = schemaVersion,
            supportedAlgorithms = setOf(algorithm),
            trustedKeys = LicenseTrustedKeyResolver {
                resolverCalls++
                trustedKey
            },
            signatureVerifier = LicenseDigestTestVerifier
        )

        val result = verifier.verify(envelope(algorithm = unsupported))
        assertEquals(
            LicenseVerificationRejection.UNSUPPORTED_ALGORITHM,
            assertIs<LicenseVerificationResult.Rejected>(result).reason
        )
        assertEquals(0, resolverCalls)
    }

    @Test
    fun incompatible_schema_fails_closed_before_key_resolution() {
        var resolverCalls = 0
        val verifier = LicenseVerifier(
            supportedSchemaVersion = schemaVersion,
            supportedAlgorithms = setOf(algorithm),
            trustedKeys = LicenseTrustedKeyResolver {
                resolverCalls++
                trustedKey
            },
            signatureVerifier = LicenseDigestTestVerifier
        )

        val result = verifier.verify(envelope(schemaVersion = LicenseVersion(2)))
        assertEquals(
            LicenseVerificationRejection.UNSUPPORTED_SCHEMA_VERSION,
            assertIs<LicenseVerificationResult.Rejected>(result).reason
        )
        assertEquals(0, resolverCalls)
    }

    @Test
    fun invalid_signature_fails_closed_before_canonical_payload_can_be_accepted() {
        val result = composition().verify(envelope(corruptSignature = true))
        assertEquals(
            LicenseVerificationRejection.INVALID_SIGNATURE,
            assertIs<LicenseVerificationResult.Rejected>(result).reason
        )
    }

    @Test
    fun signed_but_malformed_canonical_payload_fails_closed() {
        val malformed = "not-a-license-entitlement".licenseCanonicalPayload()
        val result = composition().verify(envelope(payloadOverride = malformed))
        assertEquals(
            LicenseVerificationRejection.INVALID_CANONICAL_PAYLOAD,
            assertIs<LicenseVerificationResult.Rejected>(result).reason
        )
    }

    @Test
    fun signed_entitlement_key_id_must_match_envelope_key_id() {
        val payloadEntitlement = entitlement(signingKeyId = LicenseKeyId("different-key"))
        val result = composition().verify(envelope(entitlement = payloadEntitlement))
        assertEquals(
            LicenseVerificationRejection.SIGNING_KEY_ID_MISMATCH,
            assertIs<LicenseVerificationResult.Rejected>(result).reason
        )
    }

    @Test
    fun resolver_cannot_substitute_a_different_trusted_key_identity() {
        val wrongKey = LicenseTrustedVerificationKey.of(
            keyId = LicenseKeyId("different-key"),
            algorithm = algorithm,
            material = byteArrayOf(4, 5, 6)
        )
        val result = composition(
            resolver = LicenseTrustedKeyResolver { wrongKey }
        ).verify(envelope())

        assertEquals(
            LicenseVerificationRejection.TRUSTED_KEY_MISMATCH,
            assertIs<LicenseVerificationResult.Rejected>(result).reason
        )
    }

    @Test
    fun envelope_rendering_never_exposes_payload_signature_or_private_subject() {
        val signed = envelope()
        val rendered = signed.toString()
        assertFalse(rendered.contains("private-subject-reference"))
        assertTrue(rendered.contains("payload=<redacted>"))
        assertTrue(rendered.contains("signature=<redacted>"))
    }
}
