package pro.liliya.core.protectedmodel

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.Test

class LargeProtectedModelCanonicalDecodingContractTest {
    @Test
    fun manifest_encode_decode_encode_is_exact() {
        val manifest = manifest()
        val encoded = LargeProtectedModelManifestCanonicalCodec.encode(manifest)

        val decoded = assertIs<LargeProtectedModelManifestCanonicalDecodeResult.Decoded>(
            LargeProtectedModelManifestCanonicalDecoder.decode(encoded, budgets())
        ).manifest

        assertEquals(manifest.profile, decoded.profile)
        assertEquals(manifest.model, decoded.model)
        assertEquals(manifest.modelDek, decoded.modelDek)
        assertEquals(manifest.totalPlaintextSizeBytes, decoded.totalPlaintextSizeBytes)
        assertEquals(manifest.segmentCount, decoded.segmentCount)
        assertContentEquals(encoded, LargeProtectedModelManifestCanonicalCodec.encode(decoded))
    }

    @Test
    fun malformed_trailing_and_oversized_manifest_fail_closed() {
        val encoded = LargeProtectedModelManifestCanonicalCodec.encode(manifest())
        val truncated = encoded.copyOf(encoded.size - 1)
        val trailing = encoded + byteArrayOf(1)

        assertEquals(
            LargeProtectedModelCanonicalDecodeFailure.MALFORMED,
            assertIs<LargeProtectedModelManifestCanonicalDecodeResult.Rejected>(
                LargeProtectedModelManifestCanonicalDecoder.decode(truncated, budgets())
            ).reason
        )
        assertEquals(
            LargeProtectedModelCanonicalDecodeFailure.TRAILING_DATA,
            assertIs<LargeProtectedModelManifestCanonicalDecodeResult.Rejected>(
                LargeProtectedModelManifestCanonicalDecoder.decode(trailing, budgets())
            ).reason
        )
        assertEquals(
            LargeProtectedModelCanonicalDecodeFailure.RESOURCE_LIMIT_REJECTED,
            assertIs<LargeProtectedModelManifestCanonicalDecodeResult.Rejected>(
                LargeProtectedModelManifestCanonicalDecoder.decode(
                    encoded,
                    budgets(maxCanonicalManifestBytes = encoded.size.toLong() - 1L)
                )
            ).reason
        )
    }

    @Test
    fun signed_manifest_round_trip_is_exact_and_does_not_verify_signature() {
        val signed = LargeProtectedModelSignedManifest(
            formatVersion = ProtectedModelFormatVersion(1),
            modelProfileId = ProtectedModelProfileId("gguf-product-v1"),
            payload = manifest(),
            encryptionProfile = ProtectedModelEncryptionProfile.AES_256_GCM,
            signatureAlgorithm = ProtectedModelSignatureAlgorithm.ED25519,
            signerId = ProtectedModelSignerId("trusted-signer")
        )
        val encoded = LargeProtectedModelPackageCanonicalCodec.encode(signed)

        val decoded = assertIs<LargeProtectedModelSignedManifestCanonicalDecodeResult.Decoded>(
            LargeProtectedModelSignedManifestCanonicalDecoder.decode(
                bytes = encoded,
                manifestBudgets = budgets(),
                packageBudgets = packageBudgets()
            )
        ).manifest

        assertEquals(signed.formatVersion, decoded.formatVersion)
        assertEquals(signed.modelProfileId, decoded.modelProfileId)
        assertEquals(signed.payload.model, decoded.payload.model)
        assertEquals(signed.signerId, decoded.signerId)
        assertContentEquals(encoded, LargeProtectedModelPackageCanonicalCodec.encode(decoded))
    }

    @Test
    fun signed_manifest_rejects_trailing_and_unsupported_canonical_version() {
        val signed = LargeProtectedModelSignedManifest(
            formatVersion = ProtectedModelFormatVersion(1),
            modelProfileId = ProtectedModelProfileId("gguf-product-v1"),
            payload = manifest(),
            encryptionProfile = ProtectedModelEncryptionProfile.AES_256_GCM,
            signatureAlgorithm = ProtectedModelSignatureAlgorithm.ED25519,
            signerId = ProtectedModelSignerId("trusted-signer")
        )
        val encoded = LargeProtectedModelPackageCanonicalCodec.encode(signed)
        val unsupported = encoded.copyOf().also {
            it[0] = 0
            it[1] = 0
            it[2] = 0
            it[3] = 2
        }

        assertEquals(
            LargeProtectedModelCanonicalDecodeFailure.TRAILING_DATA,
            assertIs<LargeProtectedModelSignedManifestCanonicalDecodeResult.Rejected>(
                LargeProtectedModelSignedManifestCanonicalDecoder.decode(
                    encoded + byteArrayOf(0), budgets(), packageBudgets()
                )
            ).reason
        )
        assertEquals(
            LargeProtectedModelCanonicalDecodeFailure.UNSUPPORTED_VERSION,
            assertIs<LargeProtectedModelSignedManifestCanonicalDecodeResult.Rejected>(
                LargeProtectedModelSignedManifestCanonicalDecoder.decode(
                    unsupported, budgets(), packageBudgets()
                )
            ).reason
        )
    }

    private fun manifest(): LargeProtectedModelManifest {
        val request = LargeProtectedModelManifestRequest(
            profile = LargeProtectedModelPayloadProfile.SEGMENTED_AES_256_GCM_SHA256_V1,
            model = ProtectedModelReference(
                ProtectedModelPackageId("local-import-model"),
                ProtectedModelGeneration(4)
            ),
            modelDek = ModelDekReference(
                ModelDekId("local-import-dek"),
                ModelDekGeneration(2)
            ),
            totalPlaintextSizeBytes = 10,
            totalCiphertextBodySizeBytes = 10,
            totalProtectedPayloadSizeBytes = 42,
            declaredSegmentCount = 2,
            segments = listOf(
                LargeProtectedModelSegmentDraft(
                    index = 0,
                    plaintextSizeBytes = 5,
                    ciphertextBodySizeBytes = 5,
                    nonce = ByteArray(12) { (it + 1).toByte() },
                    protectedPayloadDigest = ByteArray(32) { 3 }
                ),
                LargeProtectedModelSegmentDraft(
                    index = 1,
                    plaintextSizeBytes = 5,
                    ciphertextBodySizeBytes = 5,
                    nonce = ByteArray(12) { (it + 21).toByte() },
                    protectedPayloadDigest = ByteArray(32) { 7 }
                )
            )
        )
        return assertIs<LargeProtectedModelManifestResult.Accepted>(
            LargeProtectedModelManifestFactory.create(request, budgets())
        ).manifest
    }

    private fun budgets(maxCanonicalManifestBytes: Long = 64 * 1024L) =
        LargeProtectedModelResourceBudgets(
            maxTotalPlaintextBytes = 1024,
            maxTotalCiphertextBodyBytes = 1024,
            maxTotalProtectedPayloadBytes = 2048,
            maxSegmentCount = 8,
            minNonFinalSegmentPlaintextBytes = 1,
            maxSegmentPlaintextBytes = 512,
            maxSegmentCiphertextBodyBytes = 512,
            maxStructuralIdentifierChars = 128,
            maxCanonicalManifestBytes = maxCanonicalManifestBytes
        )

    private fun packageBudgets() = LargeProtectedModelPackageBudgets(
        maxModelProfileIdChars = 128,
        maxSignerIdChars = 128,
        maxCanonicalSignedManifestBytes = 128 * 1024L
    )
}
