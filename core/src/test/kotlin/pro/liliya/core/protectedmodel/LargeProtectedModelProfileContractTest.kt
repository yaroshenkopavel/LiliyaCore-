package pro.liliya.core.protectedmodel

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LargeProtectedModelProfileContractTest {
    @Test
    fun valid_single_segment_manifest_is_accepted() {
        val result = LargeProtectedModelManifestFactory.create(
            request(
                totalPlaintext = 100,
                totalCiphertext = 116,
                drafts = listOf(segment(0, 100, 116, nonceSeed = 1))
            ),
            budgets(maxSegments = 2, maxTotal = 200, maxSegment = 120)
        )

        val manifest = assertIs<LargeProtectedModelManifestResult.Accepted>(result).manifest
        assertEquals(1, manifest.segmentCount)
        assertEquals(100, manifest.totalPlaintextSizeBytes)
        assertEquals(116, manifest.totalCiphertextSizeBytes)
        assertEquals(0, manifest.segments().single().index)
    }

    @Test
    fun valid_multi_segment_manifest_preserves_exact_order() {
        val result = LargeProtectedModelManifestFactory.create(
            request(
                totalPlaintext = 250,
                totalCiphertext = 298,
                drafts = listOf(
                    segment(0, 100, 116, nonceSeed = 1),
                    segment(1, 100, 116, nonceSeed = 2),
                    segment(2, 50, 66, nonceSeed = 3)
                )
            ),
            budgets(maxSegments = 3, maxTotal = 400, maxSegment = 120)
        )

        val manifest = assertIs<LargeProtectedModelManifestResult.Accepted>(result).manifest
        assertEquals(listOf(0, 1, 2), manifest.segments().map { it.index })
    }

    @Test
    fun unsupported_profile_rejects() {
        val unsupported = LargeProtectedModelPayloadProfile(
            LargeProtectedModelPayloadProfileId("OTHER_PROFILE"),
            LargeProtectedModelPayloadProfileVersion(1)
        )
        val rejected = assertRejected(
            request(
                totalPlaintext = 100,
                totalCiphertext = 116,
                drafts = listOf(segment(0, 100, 116, 1)),
                profile = unsupported
            ),
            budgets()
        )

        assertEquals(LargeProtectedModelManifestFailure.UNSUPPORTED_PROFILE, rejected.reason)
    }

    @Test
    fun declared_segment_count_must_be_positive_bounded_and_match_list() {
        val draft = segment(0, 100, 116, 1)

        assertEquals(
            LargeProtectedModelManifestFailure.SEGMENT_COUNT_INVALID,
            assertRejected(request(100, 116, listOf(draft), declaredCount = 0), budgets()).reason
        )
        assertEquals(
            LargeProtectedModelManifestFailure.SEGMENT_COUNT_INVALID,
            assertRejected(request(100, 116, listOf(draft), declaredCount = 3), budgets(maxSegments = 2)).reason
        )
        assertEquals(
            LargeProtectedModelManifestFailure.SEGMENT_COUNT_MISMATCH,
            assertRejected(request(100, 116, listOf(draft), declaredCount = 2), budgets(maxSegments = 2)).reason
        )
    }

    @Test
    fun duplicate_out_of_range_and_reordered_indices_fail_closed() {
        val duplicate = request(
            200,
            232,
            listOf(segment(0, 100, 116, 1), segment(0, 100, 116, 2)),
            declaredCount = 2
        )
        assertEquals(
            LargeProtectedModelManifestFailure.DUPLICATE_SEGMENT_INDEX,
            assertRejected(duplicate, budgets(maxSegments = 2, maxTotal = 300, maxSegment = 120)).reason
        )

        val outOfRange = request(
            200,
            232,
            listOf(segment(0, 100, 116, 1), segment(2, 100, 116, 2)),
            declaredCount = 2
        )
        assertEquals(
            LargeProtectedModelManifestFailure.SEGMENT_INDEX_INVALID,
            assertRejected(outOfRange, budgets(maxSegments = 2, maxTotal = 300, maxSegment = 120)).reason
        )

        val reordered = request(
            200,
            232,
            listOf(segment(1, 100, 116, 1), segment(0, 100, 116, 2)),
            declaredCount = 2
        )
        assertEquals(
            LargeProtectedModelManifestFailure.SEGMENT_ORDER_INVALID,
            assertRejected(reordered, budgets(maxSegments = 2, maxTotal = 300, maxSegment = 120)).reason
        )
    }

    @Test
    fun deliberately_non_default_budgets_are_enforced() {
        val custom = LargeProtectedModelResourceBudgets(
            maxTotalPlaintextBytes = 250,
            maxTotalCiphertextBytes = 300,
            maxSegmentCount = 2,
            minSegmentPlaintextBytes = 40,
            maxSegmentPlaintextBytes = 110,
            maxSegmentCiphertextBytes = 130
        )

        val tooSmall = request(
            139,
            171,
            listOf(segment(0, 100, 116, 1), segment(1, 39, 55, 2)),
            declaredCount = 2
        )
        assertEquals(
            LargeProtectedModelManifestFailure.SEGMENT_PLAINTEXT_SIZE_INVALID,
            assertRejected(tooSmall, custom).reason
        )

        val accepted = LargeProtectedModelManifestFactory.create(
            request(
                140,
                172,
                listOf(segment(0, 100, 116, 1), segment(1, 40, 56, 2)),
                declaredCount = 2
            ),
            custom
        )
        assertIs<LargeProtectedModelManifestResult.Accepted>(accepted)
    }

    @Test
    fun total_and_aggregate_sizes_must_match_exactly() {
        val custom = budgets(maxSegments = 2, maxTotal = 400, maxSegment = 160)
        val drafts = listOf(segment(0, 100, 116, 1), segment(1, 100, 116, 2))

        assertEquals(
            LargeProtectedModelManifestFailure.AGGREGATE_PLAINTEXT_SIZE_MISMATCH,
            assertRejected(request(201, 232, drafts, 2), custom).reason
        )
        assertEquals(
            LargeProtectedModelManifestFailure.AGGREGATE_CIPHERTEXT_SIZE_MISMATCH,
            assertRejected(request(200, 233, drafts, 2), custom).reason
        )
        assertEquals(
            LargeProtectedModelManifestFailure.TOTAL_PLAINTEXT_SIZE_INVALID,
            assertRejected(request(401, 232, drafts, 2), custom).reason
        )
    }

    @Test
    fun aggregate_overflow_is_typed_without_large_allocations() {
        val huge = Long.MAX_VALUE / 2 + 1
        val overflowBudgets = LargeProtectedModelResourceBudgets(
            maxTotalPlaintextBytes = Long.MAX_VALUE,
            maxTotalCiphertextBytes = Long.MAX_VALUE,
            maxSegmentCount = 2,
            minSegmentPlaintextBytes = 1,
            maxSegmentPlaintextBytes = Long.MAX_VALUE,
            maxSegmentCiphertextBytes = Long.MAX_VALUE
        )
        val rejected = assertRejected(
            request(
                totalPlaintext = Long.MAX_VALUE,
                totalCiphertext = Long.MAX_VALUE,
                drafts = listOf(
                    segment(0, huge, huge, 1),
                    segment(1, huge, huge, 2)
                ),
                declaredCount = 2
            ),
            overflowBudgets
        )

        assertEquals(LargeProtectedModelManifestFailure.AGGREGATE_SIZE_OVERFLOW, rejected.reason)
    }

    @Test
    fun nonce_and_digest_shape_and_nonce_uniqueness_are_exact() {
        val invalidNonce = LargeProtectedModelSegmentDraft(
            0,
            100,
            116,
            ByteArray(11),
            ByteArray(SEGMENT_CIPHERTEXT_DIGEST_SIZE_BYTES)
        )
        assertEquals(
            LargeProtectedModelManifestFailure.INVALID_NONCE_SIZE,
            assertRejected(request(100, 116, listOf(invalidNonce)), budgets()).reason
        )

        val invalidDigest = LargeProtectedModelSegmentDraft(
            0,
            100,
            116,
            ByteArray(SEGMENT_NONCE_SIZE_BYTES),
            ByteArray(31)
        )
        assertEquals(
            LargeProtectedModelManifestFailure.INVALID_CIPHERTEXT_DIGEST_SIZE,
            assertRejected(request(100, 116, listOf(invalidDigest)), budgets()).reason
        )

        val sameNonceA = segment(0, 100, 116, 7)
        val sameNonceB = segment(1, 100, 116, 7)
        assertEquals(
            LargeProtectedModelManifestFailure.DUPLICATE_NONCE,
            assertRejected(
                request(200, 232, listOf(sameNonceA, sameNonceB), declaredCount = 2),
                budgets(maxSegments = 2, maxTotal = 300, maxSegment = 120)
            ).reason
        )
    }

    @Test
    fun mutable_nonce_and_digest_inputs_are_detached_and_outputs_are_defensive() {
        val nonce = ByteArray(SEGMENT_NONCE_SIZE_BYTES) { (it + 1).toByte() }
        val digest = ByteArray(SEGMENT_CIPHERTEXT_DIGEST_SIZE_BYTES) { (it + 20).toByte() }
        val originalNonce = nonce.copyOf()
        val originalDigest = digest.copyOf()
        val draft = LargeProtectedModelSegmentDraft(0, 100, 116, nonce, digest)

        nonce.fill(99)
        digest.fill(99)

        val manifest = assertIs<LargeProtectedModelManifestResult.Accepted>(
            LargeProtectedModelManifestFactory.create(
                request(100, 116, listOf(draft)),
                budgets()
            )
        ).manifest
        val segment = manifest.segments().single()
        assertContentEquals(originalNonce, segment.copyNonce())
        assertContentEquals(originalDigest, segment.copyCiphertextDigest())

        val nonceCopy = segment.copyNonce()
        val digestCopy = segment.copyCiphertextDigest()
        nonceCopy.fill(0)
        digestCopy.fill(0)
        assertContentEquals(originalNonce, segment.copyNonce())
        assertContentEquals(originalDigest, segment.copyCiphertextDigest())
    }

    @Test
    fun rendering_redacts_cryptographic_bytes_and_existing_sensitive_ids() {
        val result = assertIs<LargeProtectedModelManifestResult.Accepted>(
            LargeProtectedModelManifestFactory.create(
                request(100, 116, listOf(segment(0, 100, 116, 1))),
                budgets()
            )
        )

        val rendered = result.manifest.toString() + result.manifest.segments().single().toString()
        assertFalse(rendered.contains("package-secret"))
        assertFalse(rendered.contains("model-dek-secret"))
        assertFalse(rendered.contains("1, 2, 3"))
        assertTrue(rendered.contains("<redacted:"))
    }

    @Test
    fun canonical_encoding_is_deterministic_and_changes_with_security_critical_structure() {
        val first = acceptedManifest(
            listOf(segment(0, 100, 116, 1), segment(1, 50, 66, 2)),
            150,
            182
        )
        val same = acceptedManifest(
            listOf(segment(0, 100, 116, 1), segment(1, 50, 66, 2)),
            150,
            182
        )
        val changed = acceptedManifest(
            listOf(segment(0, 100, 116, 1), segment(1, 50, 66, 3)),
            150,
            182
        )

        val firstBytes = LargeProtectedModelManifestCanonicalCodec.encode(first)
        val sameBytes = LargeProtectedModelManifestCanonicalCodec.encode(same)
        val changedBytes = LargeProtectedModelManifestCanonicalCodec.encode(changed)
        assertContentEquals(firstBytes, sameBytes)
        assertFalse(firstBytes.contentEquals(changedBytes))
    }

    @Test
    fun reusable_factory_is_stateless_under_concurrent_validation() {
        val request = request(
            150,
            182,
            listOf(segment(0, 100, 116, 1), segment(1, 50, 66, 2)),
            declaredCount = 2
        )
        val custom = budgets(maxSegments = 2, maxTotal = 300, maxSegment = 120)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = pool.invokeAll(
                List(64) {
                    Callable {
                        val result = LargeProtectedModelManifestFactory.create(request, custom)
                        val manifest = assertIs<LargeProtectedModelManifestResult.Accepted>(result).manifest
                        LargeProtectedModelManifestCanonicalCodec.encode(manifest)
                    }
                }
            )
            val baseline = futures.first().get()
            futures.forEach { assertContentEquals(baseline, it.get()) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun frozen_direct_payload_types_remain_unchanged_and_usable() {
        val oldProfile = ProtectedModelEncryptionProfile.AES_256_GCM
        assertEquals(ProtectedModelEncryptionAlgorithm.AES_256_GCM, oldProfile.algorithm)
        assertEquals(12, oldProfile.nonceSizeBytes)
        assertEquals(128, oldProfile.authenticationTagSizeBits)
        assertNotEquals(
            LargeProtectedModelPayloadProfile.SEGMENTED_AES_256_GCM_SHA256_V1.id.value,
            ProtectedModelProfileId("direct-v0.1").toString()
        )
    }

    private fun acceptedManifest(
        drafts: List<LargeProtectedModelSegmentDraft>,
        totalPlaintext: Long,
        totalCiphertext: Long
    ): LargeProtectedModelManifest = assertIs<LargeProtectedModelManifestResult.Accepted>(
        LargeProtectedModelManifestFactory.create(
            request(totalPlaintext, totalCiphertext, drafts, drafts.size),
            budgets(maxSegments = drafts.size, maxTotal = 400, maxSegment = 160)
        )
    ).manifest

    private fun assertRejected(
        request: LargeProtectedModelManifestRequest,
        budgets: LargeProtectedModelResourceBudgets
    ): LargeProtectedModelManifestResult.Rejected =
        assertIs(LargeProtectedModelManifestFactory.create(request, budgets))

    private fun request(
        totalPlaintext: Long,
        totalCiphertext: Long,
        drafts: List<LargeProtectedModelSegmentDraft>,
        declaredCount: Int = drafts.size,
        profile: LargeProtectedModelPayloadProfile =
            LargeProtectedModelPayloadProfile.SEGMENTED_AES_256_GCM_SHA256_V1
    ) = LargeProtectedModelManifestRequest(
        profile = profile,
        model = ProtectedModelReference(
            ProtectedModelPackageId("package-secret"),
            ProtectedModelGeneration(7)
        ),
        modelDek = ModelDekReference(
            ModelDekId("model-dek-secret"),
            ModelDekGeneration(9)
        ),
        totalPlaintextSizeBytes = totalPlaintext,
        totalCiphertextSizeBytes = totalCiphertext,
        declaredSegmentCount = declaredCount,
        segments = drafts
    )

    private fun segment(
        index: Int,
        plaintext: Long,
        ciphertext: Long,
        nonceSeed: Int
    ) = LargeProtectedModelSegmentDraft(
        index = index,
        plaintextSizeBytes = plaintext,
        ciphertextSizeBytes = ciphertext,
        nonce = ByteArray(SEGMENT_NONCE_SIZE_BYTES) { (nonceSeed + it).toByte() },
        ciphertextDigest = ByteArray(SEGMENT_CIPHERTEXT_DIGEST_SIZE_BYTES) {
            (nonceSeed * 3 + it).toByte()
        }
    )

    private fun budgets(
        maxSegments: Int = 1,
        maxTotal: Long = 200,
        maxSegment: Long = 120
    ) = LargeProtectedModelResourceBudgets(
        maxTotalPlaintextBytes = maxTotal,
        maxTotalCiphertextBytes = maxTotal + 100,
        maxSegmentCount = maxSegments,
        minSegmentPlaintextBytes = 1,
        maxSegmentPlaintextBytes = maxSegment,
        maxSegmentCiphertextBytes = maxSegment + 20
    )
}
