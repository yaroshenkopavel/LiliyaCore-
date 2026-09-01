package pro.liliya.core.license

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LicenseServiceDurableStateEnvelopeContractTest {
    private fun binding(
        storeId: String = "PRIVATE-LICENSE-STORE",
        generation: Long = 9L,
        backendRevision: Long = 4L,
        protectorId: String = "PRIVATE-LICENSE-PROTECTOR",
        protectorGeneration: Long = 3L
    ) = LicenseServiceDurableStateBinding(
        version = LicenseServiceDurableStateEnvelopeVersion(1),
        purpose = LicenseServiceDurableStatePurpose.LICENSE_SERVICE_SECURITY_STATE,
        profile = LicenseServiceDurableStateEncryptionProfile.AES_256_GCM,
        storeId = LicenseServiceDurableStoreId(storeId),
        generation = LicenseServiceDurableStateGeneration(generation),
        backendRevision = LicenseServiceDurableBackendRevision(backendRevision),
        protector = LicenseServiceDurableStateProtectorReference(
            id = LicenseServiceDurableStateProtectorId(protectorId),
            generation = LicenseServiceDurableStateProtectorGeneration(protectorGeneration)
        )
    )

    private fun envelope(
        binding: LicenseServiceDurableStateBinding = binding(),
        nonce: ByteArray = ByteArray(12) { (it + 1).toByte() },
        ciphertext: ByteArray = byteArrayOf(41, 42, 43, 44),
        tag: ByteArray = ByteArray(16) { (it + 21).toByte() }
    ) = LicenseServiceDurableStateEnvelope(binding, nonce, ciphertext, tag)

    @Test
    fun sealed_envelope_round_trips_exact_binding_and_redacts_private_material() {
        val original = envelope()
        val encoded = assertIs<LicenseServiceDurableStateEnvelopeEncodeResult.Encoded>(
            LicenseServiceDurableStateEnvelopeCanonicalCodec.encode(original)
        ).payload
        val decoded = assertIs<LicenseServiceDurableStateEnvelopeDecodeResult.Decoded>(
            LicenseServiceDurableStateEnvelopeCanonicalCodec.decode(encoded)
        ).envelope

        assertEquals(original, decoded)
        assertEquals(1, decoded.binding.stateSchemaVersion.value)
        assertEquals(9L, decoded.binding.generation.value)
        assertEquals(4L, decoded.binding.backendRevision.value)
        assertEquals(3L, decoded.binding.protector.generation.value)

        val rendered = buildString {
            append(original.binding)
            append(original)
            append(encoded)
            append(decoded)
        }
        assertFalse("PRIVATE-LICENSE-STORE" in rendered)
        assertFalse("PRIVATE-LICENSE-PROTECTOR" in rendered)
        assertFalse("41, 42, 43, 44" in rendered)
        assertTrue("<redacted>" in rendered)
    }

    @Test
    fun envelope_defensively_copies_nonce_ciphertext_and_tag() {
        val nonce = ByteArray(12) { 1 }
        val ciphertext = byteArrayOf(2, 3, 4)
        val tag = ByteArray(16) { 5 }
        val original = envelope(nonce = nonce, ciphertext = ciphertext, tag = tag)

        nonce.fill(9)
        ciphertext.fill(9)
        tag.fill(9)

        assertTrue(original.copyNonce().all { it == 1.toByte() })
        assertTrue(original.copyCiphertext().contentEquals(byteArrayOf(2, 3, 4)))
        assertTrue(original.copyAuthenticationTag().all { it == 5.toByte() })

        val returned = original.copyCiphertext()
        returned.fill(8)
        assertTrue(original.copyCiphertext().contentEquals(byteArrayOf(2, 3, 4)))
    }

    @Test
    fun associated_data_changes_for_every_exact_ownership_or_destination_field() {
        val base = LicenseServiceDurableStateAssociatedDataEncoder.encode(binding())
        val variants = listOf(
            binding(storeId = "PRIVATE-LICENSE-STORE-OTHER"),
            binding(generation = 10L),
            binding(backendRevision = 5L),
            binding(protectorId = "PRIVATE-LICENSE-PROTECTOR-OTHER"),
            binding(protectorGeneration = 4L)
        )

        variants.forEach { variant ->
            val encoded = LicenseServiceDurableStateAssociatedDataEncoder.encode(variant)
            assertFalse(base.contentEquals(encoded))
        }
    }

    @Test
    fun oversized_or_malformed_structural_material_fails_before_envelope_use() {
        val oversizedId = "x".repeat(LicenseServiceDurableStateEnvelopeCanonicalCodec.MAX_ID_BYTES + 1)
        assertFailsWith<IllegalArgumentException> {
            LicenseServiceDurableStoreId(oversizedId)
        }
        assertFailsWith<IllegalArgumentException> {
            LicenseServiceDurableStateProtectorId(oversizedId)
        }
        assertFailsWith<IllegalArgumentException> {
            envelope(ciphertext = ByteArray(
                LicenseServiceDurableStateEnvelopeCanonicalCodec.MAX_CIPHERTEXT_BYTES + 1
            ))
        }
        assertFailsWith<IllegalArgumentException> {
            envelope(nonce = ByteArray(11))
        }
        assertFailsWith<IllegalArgumentException> {
            envelope(tag = ByteArray(15))
        }
    }

    @Test
    fun envelope_decode_rejects_unsupported_versions_and_trailing_bytes() {
        val canonical = assertIs<LicenseServiceDurableStateEnvelopeEncodeResult.Encoded>(
            LicenseServiceDurableStateEnvelopeCanonicalCodec.encode(envelope())
        ).payload.copyBytes()

        val unsupportedCodec = canonical.copyOf().also { writeInt(it, 4, 2) }
        assertEquals(
            LicenseServiceDurableStateCodecRejection.UNSUPPORTED_VERSION,
            assertIs<LicenseServiceDurableStateEnvelopeDecodeResult.Rejected>(
                LicenseServiceDurableStateEnvelopeCanonicalCodec.decode(
                    LicenseServiceDurableStateEnvelopePayload.of(unsupportedCodec)
                )
            ).reason
        )

        val unsupportedEnvelope = canonical.copyOf().also { writeInt(it, 8, 2) }
        assertEquals(
            LicenseServiceDurableStateCodecRejection.UNSUPPORTED_VERSION,
            assertIs<LicenseServiceDurableStateEnvelopeDecodeResult.Rejected>(
                LicenseServiceDurableStateEnvelopeCanonicalCodec.decode(
                    LicenseServiceDurableStateEnvelopePayload.of(unsupportedEnvelope)
                )
            ).reason
        )

        val unsupportedSchema = canonical.copyOf().also { writeInt(it, 12, 2) }
        assertEquals(
            LicenseServiceDurableStateCodecRejection.UNSUPPORTED_VERSION,
            assertIs<LicenseServiceDurableStateEnvelopeDecodeResult.Rejected>(
                LicenseServiceDurableStateEnvelopeCanonicalCodec.decode(
                    LicenseServiceDurableStateEnvelopePayload.of(unsupportedSchema)
                )
            ).reason
        )

        val trailing = LicenseServiceDurableStateEnvelopePayload.of(canonical + byteArrayOf(0x55))
        assertEquals(
            LicenseServiceDurableStateCodecRejection.NON_CANONICAL,
            assertIs<LicenseServiceDurableStateEnvelopeDecodeResult.Rejected>(
                LicenseServiceDurableStateEnvelopeCanonicalCodec.decode(trailing)
            ).reason
        )
    }

    @Test
    fun envelope_decode_rejects_oversized_payload_before_internal_parse_copy() {
        val payload = LicenseServiceDurableStateEnvelopePayload.of(
            ByteArray(LicenseServiceDurableStateEnvelopeCanonicalCodec.MAX_ENVELOPE_BYTES + 1) { 1 }
        )

        val result = LicenseServiceDurableStateEnvelopeCanonicalCodec.decode(payload)

        assertEquals(
            LicenseServiceDurableStateCodecRejection.BOUNDS_EXCEEDED,
            assertIs<LicenseServiceDurableStateEnvelopeDecodeResult.Rejected>(result).reason
        )
    }

    private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(bytes, offset, Int.SIZE_BYTES).putInt(value)
    }
}
