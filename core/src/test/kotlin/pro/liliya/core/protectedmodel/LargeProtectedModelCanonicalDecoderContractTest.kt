package pro.liliya.core.protectedmodel

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LargeProtectedModelCanonicalDecoderContractTest {
    @Test
    fun canonical_signed_manifest_round_trips_without_changing_encode_bytes() {
        val manifest = signedManifest()
        val encodedBefore = LargeProtectedModelPackageCanonicalCodec.encode(manifest)

        val decoded = LargeProtectedModelCanonicalDecoder.decodeSignedManifest(
            encodedBefore,
            resourceBudgets(),
            packageBudgets()
        )
        val accepted = assertIs<LargeProtectedModelCanonicalDecodeResult.Decoded>(decoded)
        val encodedAfter = LargeProtectedModelPackageCanonicalCodec.encode(accepted.manifest)

        assertContentEquals(encodedBefore, encodedAfter)
        assertEquals(manifest.payload.model, accepted.manifest.payload.model)
        assertEquals(manifest.payload.modelDek, accepted.manifest.payload.modelDek)
        assertEquals(manifest.payload.segmentCount, accepted.manifest.payload.segmentCount)
    }

    @Test
    fun trailing_bytes_reject_fail_closed() {
        val encoded = LargeProtectedModelPackageCanonicalCodec.encode(signedManifest())
        val result = LargeProtectedModelCanonicalDecoder.decodeSignedManifest(
            encoded + byteArrayOf(1),
            resourceBudgets(),
            packageBudgets()
        )
        assertEquals(
            LargeProtectedModelCanonicalDecodeFailure.MALFORMED,
            assertIs<LargeProtectedModelCanonicalDecodeResult.Rejected>(result).reason
        )
    }

    @Test
    fun truncated_bytes_reject_fail_closed() {
        val encoded = LargeProtectedModelPackageCanonicalCodec.encode(signedManifest())
        val result = LargeProtectedModelCanonicalDecoder.decodeSignedManifest(
            encoded.copyOf(encoded.size - 1),
            resourceBudgets(),
            packageBudgets()
        )
        assertEquals(
            LargeProtectedModelCanonicalDecodeFailure.MALFORMED,
            assertIs<LargeProtectedModelCanonicalDecodeResult.Rejected>(result).reason
        )
    }

    private fun signedManifest(): LargeProtectedModelSignedManifest {
        val request = LargeProtectedModelManifestRequest(
            profile = LargeProtectedModelPayloadProfile.SEGMENTED_AES_256_GCM_SHA256_V1,
            model = ProtectedModelReference(
                ProtectedModelPackageId("local-import-model"),
                ProtectedModelGeneration(3)
            ),
            modelDek = ModelDekReference(
                ModelDekId("local-import-dek"),
                ModelDekGeneration(4)
            ),
            totalPlaintextSizeBytes = 8,
            totalCiphertextBodySizeBytes = 8,
            totalProtectedPayloadSizeBytes = 40,
            declaredSegmentCount = 2,
            segments = listOf(
                LargeProtectedModelSegmentDraft(
                    index = 0,
                    plaintextSizeBytes = 4,
                    ciphertextBodySizeBytes = 4,
                    nonce = ByteArray(12) { 1 },
                    protectedPayloadDigest = ByteArray(32) { 2 }
                ),
                LargeProtectedModelSegmentDraft(
                    index = 1,
                    plaintextSizeBytes = 4,
                    ciphertextBodySizeBytes = 4,
                    nonce = ByteArray(12) { 3 },
                    protectedPayloadDigest = ByteArray(32) { 4 }
                )
            )
        )
        val payload = assertIs<LargeProtectedModelManifestResult.Accepted>(
            LargeProtectedModelManifestFactory.create(request, resourceBudgets())
        ).manifest
        return LargeProtectedModelSignedManifest(
            formatVersion = ProtectedModelFormatVersion(1),
            modelProfileId = ProtectedModelProfileId("GGUF"),
            payload = payload,
            encryptionProfile = ProtectedModelEncryptionProfile.AES_256_GCM,
            signatureAlgorithm = ProtectedModelSignatureAlgorithm.ED25519,
            signerId = ProtectedModelSignerId("local-import-signer")
        )
    }

    private fun resourceBudgets() = LargeProtectedModelResourceBudgets(
        maxTotalPlaintextBytes = 1024,
        maxTotalCiphertextBodyBytes = 1024,
        maxTotalProtectedPayloadBytes = 2048,
        maxSegmentCount = 16,
        minNonFinalSegmentPlaintextBytes = 1,
        maxSegmentPlaintextBytes = 512,
        maxSegmentCiphertextBodyBytes = 512,
        maxStructuralIdentifierChars = 128,
        maxCanonicalManifestBytes = 32 * 1024
    )

    private fun packageBudgets() = LargeProtectedModelPackageBudgets(
        maxModelProfileIdChars = 128,
        maxSignerIdChars = 128,
        maxCanonicalSignedManifestBytes = 64 * 1024
    )
}
