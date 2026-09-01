package pro.liliya.core.protectedmodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LargeProtectedModelCanonicalResourceContractTest {
    @Test
    fun oversized_reused_structural_identifiers_reject_before_canonical_encoding() {
        val budgets = budgets(
            maxIdentifierChars = 8,
            maxCanonicalBytes = 4096
        )
        val request = request(
            packageId = "package-id-too-long",
            dekId = "dek"
        )

        val rejected = assertIs<LargeProtectedModelManifestResult.Rejected>(
            LargeProtectedModelManifestFactory.create(request, budgets)
        )

        assertEquals(
            LargeProtectedModelManifestFailure.STRUCTURAL_IDENTIFIER_SIZE_INVALID,
            rejected.reason
        )
    }

    @Test
    fun canonical_manifest_upper_bound_uses_explicit_budget() {
        val budgets = budgets(
            maxIdentifierChars = 64,
            maxCanonicalBytes = 100
        )
        val request = request(
            packageId = "package",
            dekId = "dek"
        )

        val rejected = assertIs<LargeProtectedModelManifestResult.Rejected>(
            LargeProtectedModelManifestFactory.create(request, budgets)
        )

        assertEquals(
            LargeProtectedModelManifestFailure.CANONICAL_MANIFEST_SIZE_INVALID,
            rejected.reason
        )
    }

    @Test
    fun reasonable_non_default_canonical_budget_accepts_and_codec_stays_within_it() {
        val budgets = budgets(
            maxIdentifierChars = 64,
            maxCanonicalBytes = 1024
        )
        val accepted = assertIs<LargeProtectedModelManifestResult.Accepted>(
            LargeProtectedModelManifestFactory.create(
                request(packageId = "package", dekId = "dek"),
                budgets
            )
        )

        val encoded = LargeProtectedModelManifestCanonicalCodec.encode(accepted.manifest)
        assertEquals(true, encoded.size.toLong() <= budgets.maxCanonicalManifestBytes)
    }

    private fun request(
        packageId: String,
        dekId: String
    ) = LargeProtectedModelManifestRequest(
        profile = LargeProtectedModelPayloadProfile.SEGMENTED_AES_256_GCM_SHA256_V1,
        model = ProtectedModelReference(
            ProtectedModelPackageId(packageId),
            ProtectedModelGeneration(1)
        ),
        modelDek = ModelDekReference(
            ModelDekId(dekId),
            ModelDekGeneration(1)
        ),
        totalPlaintextSizeBytes = 100,
        totalCiphertextBodySizeBytes = 100,
        totalProtectedPayloadSizeBytes = 116,
        declaredSegmentCount = 1,
        segments = listOf(
            LargeProtectedModelSegmentDraft(
                index = 0,
                plaintextSizeBytes = 100,
                ciphertextBodySizeBytes = 100,
                nonce = ByteArray(SEGMENT_NONCE_SIZE_BYTES) { (it + 1).toByte() },
                protectedPayloadDigest = ByteArray(SEGMENT_PROTECTED_PAYLOAD_DIGEST_SIZE_BYTES) {
                    (it + 7).toByte()
                }
            )
        )
    )

    private fun budgets(
        maxIdentifierChars: Int,
        maxCanonicalBytes: Long
    ) = LargeProtectedModelResourceBudgets(
        maxTotalPlaintextBytes = 200,
        maxTotalCiphertextBodyBytes = 200,
        maxTotalProtectedPayloadBytes = 300,
        maxSegmentCount = 2,
        minNonFinalSegmentPlaintextBytes = 1,
        maxSegmentPlaintextBytes = 120,
        maxSegmentCiphertextBodyBytes = 120,
        maxStructuralIdentifierChars = maxIdentifierChars,
        maxCanonicalManifestBytes = maxCanonicalBytes
    )
}
