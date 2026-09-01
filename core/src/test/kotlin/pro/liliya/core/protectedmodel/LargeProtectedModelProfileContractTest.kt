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
                totalCiphertextBody = 100,
                totalProtectedPayload = 116,
                drafts = listOf(segment(0, 100, 100, nonceSeed = 1))
            ),
            budgets(maxSegments = 2, maxTotal = 200, maxSegment = 120)
        )

        val manifest = assertIs<LargeProtectedModelManifestResult.Accepted>(result).manifest
        assertEquals(1, manifest.segmentCount)
        assertEquals(100, manifest.totalPlaintextSizeBytes)
        assertEquals(100, manifest.totalCiphertextBodySizeBytes)
        assertEquals(116, manifest.totalProtectedPayloadSizeBytes)
        assertEquals(116, manifest.segments().single().protectedPayloadSizeBytes)
        assertEquals(0, manifest.segments().single().index)
    }

    @Test
    fun valid_multi_segment_manifest_preserves_exact_order_and_tag_overhead() {
        val result = LargeProtectedModelManifestFactory.create(
            request(
                totalPlaintext = 250,
                totalCiphertextBody = 250,
                totalProtectedPayload = 298,
                drafts = listOf(
                    segment(0, 100, 100, nonceSeed = 1),
                    segment(1, 100, 100, nonceSeed = 2),
                    segment(2, 50, 50, nonceSeed = 3)
                )
            ),
            budgets(maxSegments = 3, maxTotal = 400, maxSegment = 120)
        )

        val manifest = assertIs<LargeProtectedModelManifestResult.Accepted>(result).manifest
        assertEquals(listOf(0, 1, 2), manifest.segments().map { it.index })
        assertEquals(listOf(116L, 116L, 66L), manifest.segments().map { it.protectedPayloadSizeBytes })
    }

    @Test
    fun unsupported_profile_rejects() {
        val unsupported = LargeProtectedModelPayloadProfile(
            LargeProtectedModelPayloadProfileId("OTHER_PROFILE"),
            LargeProtectedModelPayloadProfileVersion(1)
        )
        val rejected = assertRejected(
            request(100, 100, 116, listOf(segment(0, 100, 100, 1)), profile = unsupported),
            budgets()
        )

        assertEquals(LargeProtectedModelManifestFailure.UNSUPPORTED_PROFILE, rejected.reason)
    }

    @Test
    fun declared_segment_count_must_be_positive_bounded_and_match_list() {
        val draft = segment(0, 100, 100, 1)

        assertEquals(
            LargeProtectedModelManifestFailure.SEGMENT_COUNT_INVALID,
            assertRejected(request(100, 100, 116, listOf(draft), declaredCount = 0), budgets()).reason
        )
        assertEquals(
            LargeProtectedModelManifestFailure.SEGMENT_COUNT_INVALID,
            assertRejected(
                request(100, 100, 116, listOf(draft), declaredCount = 3),
                budgets(maxSegments = 2)
            ).reason
        )
        assertEquals(
            LargeProtectedModelManifestFailure.SEGMENT_COUNT_MISMATCH,
            assertRejected(
                request(100, 100, 116, listOf(draft), declaredCount = 2),
                budgets(maxSegments = 2)
            ).reason
        )
    }

    @Test
    fun duplicate_out_of_range_and_reordered_indices_fail_closed() {
        val duplicate = request(
            200,
            200,
            232,
            listOf(segment(0, 100, 100, 1), segment(0, 100, 100, 2)),
            declaredCount = 2
        )
        assertEquals(
            LargeProtectedModelManifestFailure.DUPLICATE_SEGMENT_INDEX,
            assertRejected(duplicate, budgets(maxSegments = 2, maxTotal = 300, maxSegment = 120)).reason
        )

        val outOfRange = request(
            200,
            200,
            232,
            listOf(segment(0, 100, 100, 1), segment(2, 100, 100, 2)),
            declaredCount = 2
        )
        assertEquals(
            LargeProtectedModelManifestFailure.SEGMENT_INDEX_INVALID,
            assertRejected(outOfRange, budgets(maxSegments = 2, maxTotal = 300, maxSegment = 120)).reason
        )

        val reordered = request(
            200,
            200,
            232,
            listOf(segment(1, 100, 100, 1), segment(0, 100, 100, 2)),
            declaredCount = 2
        )
        assertEquals(
            LargeProtectedModelManifestFailure.SEGMENT_ORDER_INVALID,
            assertRejected(reordered, budgets(maxSegments = 2, maxTotal = 300, maxSegment = 120)).reason
        )
    }

    @Test
    fun non_default_budget_enforces_non_final_minimum_but_allows_short_final_segment() {
        val custom = LargeProtectedModelResourceBudgets(
            maxTotalPlaintextBytes = 250,
            maxTotalCiphertextBodyBytes = 250,
            maxTotalProtectedPayloadBytes = 300,
            maxSegmentCount = 2,
            minNonFinalSegmentPlaintextBytes = 40,
            maxSegmentPlaintextBytes = 110,
            maxSegmentCiphertextBodyBytes = 110
        )

        val shortNonFinal = request(
            139,
            139,
            171,
            listOf(segment(0, 39, 39, 1), segment(1, 100, 100, 2)),
            declaredCount = 2
        )
        assertEquals(
            LargeProtectedModelManifestFailure.SEGMENT_PLAINTEXT_SIZE_INVALID,
            assertRejected(shortNonFinal, custom).reason
        )

        val accepted = LargeProtectedModelManifestFactory.create(
            request(
                120,
                120,
                152,
                listOf(segment(0, 100, 100, 1), segment(1, 20, 20, 2)),
                declaredCount = 2
            ),
            custom
        )
        assertIs<LargeProtectedModelManifestResult.Accepted>(accepted)
    }

    @Test
    fun ciphertext_body_must_equal_plaintext_for_uncompressed_first_profile() {
        val rejected = assertRejected(
            request(
                100,
                99,
                115,
                listOf(segment(0, 100, 99, 1))
            ),
            budgets()
        )

        assertEquals(
            LargeProtectedModelManifestFailure.CIPHERTEXT_PLAINTEXT_SIZE_MISMATCH,
            rejected.reason
        )
    }

    @Test
    fun all_three_aggregate_sizes_must_match_exactly() {
        val custom = budgets(maxSegments = 2, maxTotal = 400, maxSegment = 160)
        val drafts = listOf(segment(0, 100, 100, 1), segment(1, 100, 100, 2))

        assertEquals(
            LargeProtectedModelManifestFailure.AGGREGATE_PLAINTEXT_SIZE_MISMATCH,
            assertRejected(request(201, 200, 232, drafts, 2), custom).reason
        )
        assertEquals(
            LargeProtectedModelManifestFailure.AGGREGATE_CIPHERTEXT_BODY_SIZE_MISMATCH,
            assertRejected(request(200, 201, 232, drafts, 2), custom).reason
        )
        assertEquals(
            LargeProtectedModelManifestFailure.AGGREGATE_PROTECTED_PAYLOAD_SIZE_MISMATCH,
            assertRejected(request(200, 200, 233, drafts, 2), custom).reason
        )
        assertEquals(
            LargeProtectedModelManifestFailure.TOTAL_PLAINTEXT_SIZE_INVALID,
            assertRejected(request(401, 200, 232, drafts, 2), custom).reason
        )
    }

    @Test
    fun protected_payload_tag_addition_overflow_is_typed_without_large_allocations() {
        val huge = Long.MAX_VALUE
        val overflowBudgets = LargeProtectedModelResourceBudgets(
            maxTotalPlaintextBytes = Long.MAX_VALUE,
            maxTotalCiphertextBodyBytes = Long.MAX_VALUE,
            maxTotalProtectedPayloadBytes = Long.MAX_VALUE,
            maxSegmentCount = 1,
            minNonFinalSegmentPlaintextBytes = 1,
            maxSegmentPlaintextBytes = Long.MAX_VALUE,
            maxSegmentCiphertextBodyBytes = Long.MAX_VALUE
        )
        val rejected = assertRejected(
            request(
                totalPlaintext = huge,
                totalCiphertextBody = huge,
                totalProtectedPayload = huge,
                drafts = listOf(segment(0, huge, huge, 1))
            ),
            overflowBudgets
        )

        assertEquals(LargeProtectedModelManifestFailure.AGGREGATE_SIZE_OVERFLOW, rejected.reason)
    }

    @Test
    fun aggregate_overflow_is_typed_without_large_allocations() {
        val huge = Long.MAX_VALUE / 2 + 1
        val overflowBudgets = LargeProtectedModelResourceBudgets(
            maxTotalPlaintextBytes = Long.MAX_VALUE,
            maxTotalCiphertextBodyBytes = Long.MAX_VALUE,
            maxTotalProtectedPayloadBytes = Long.MAX_VALUE,
            maxSegmentCount = 2,
            minNonFinalSegmentPlaintextBytes = 1,
            maxSegmentPlaintextBytes = Long.MAX_VALUE,
            maxSegmentCiphertextBodyBytes = Long.MAX_VALUE
        )
        val rejected = assertRejected(
            request(
                totalPlaintext = Long.MAX_VALUE,
                totalCiphertextBody = Long.MAX_VALUE,
                totalProtectedPayload = Long.MAX_VALUE,
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
    fun nonce_and_protected_payload_digest_shape_and_nonce_uniqueness_are_exact() {
        val invalidNonce = LargeProtectedModelSegmentDraft(
            0,
            100,
            100,
            ByteArray(11),
            ByteArray(SEGMENT_PROTECTED_PAYLOAD_DIGEST_SIZE_BYTES)
        )
        assertEquals(
            LargeProtectedModelManifestFailure.INVALID_NONCE_SIZE,
            assertRejected(request(100, 100, 116, listOf(invalidNonce)), budgets()).reason
        )

        val invalidDigest = LargeProtectedModelSegmentDraft(
            0,
            100,
            100,
            ByteArray(SEGMENT_NONCE_SIZE_BYTES),
            ByteArray(31)
        )
        assertEquals(
            LargeProtectedModelManifestFailure.INVALID_PROTECTED_PAYLOAD_DIGEST_SIZE,
            assertRejected(request(100, 100, 116, listOf(invalidDigest)), budgets()).reason
        )

        val sameNonceA = segment(0, 100, 100, 7)
        val sameNonceB = segment(1, 100, 100, 7)
        assertEquals(
            LargeProtectedModelManifestFailure.DUPLICATE_NONCE,
            assertRejected(
                request(200, 200, 232, listOf(sameNonceA, sameNonceB), declaredCount = 2),
                budgets(maxSegments = 2, maxTotal = 300, maxSegment = 120)
            ).reason
        )
    }

    @Test
    fun mutable_nonce_and_digest_inputs_are_detached_and_outputs_are_defensive() {
        val nonce = ByteArray(SEGMENT_NONCE_SIZE_BYTES) { (it + 1).toByte() }
        val digest = ByteArray(SEGMENT_PROTECTED_PAYLOAD_DIGEST_SIZE_BYTES) { (it + 20).toByte() }
        val originalNonce = nonce.copyOf()
        val originalDigest = digest.copyOf()
        val draft = LargeProtectedModelSegmentDraft(0, 100, 100, nonce, digest)

        nonce.fill(99)
        digest.fill(99)

        val manifest = assertIs<LargeProtectedModelManifestResult.Accepted>(
            LargeProtectedModelManifestFactory.create(
                request(100, 100, 116, listOf(draft)),
                budgets()
            )
        ).manifest
        val segment = manifest.segments().single()
        assertContentEquals(originalNonce, segment.copyNonce())
        assertContentEquals(originalDigest, segment.copyProtectedPayloadDigest())

        val nonceCopy = segment.copyNonce()
        val digestCopy = segment.copyProtectedPayloadDigest()
        nonceCopy.fill(0)
        digestCopy.fill(0)
        assertContentEquals(originalNonce, segment.copyNonce())
        assertContentEquals(originalDigest, segment.copyProtectedPayloadDigest())
    }

    @Test
    fun rendering_redacts_cryptographic_bytes_and_existing_sensitive_ids() {
        val result = assertIs<LargeProtectedModelManifestResult.Accepted>(
            LargeProtectedModelManifestFactory.create(
                request(100, 100, 116, listOf(segment(0, 100, 100, 1))),
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
            listOf(segment(0, 100, 100, 1), segment(1, 50, 50, 2)),
            150,
            150,
            182
        )
        val same = acceptedManifest(
            listOf(segment(0, 100, 100, 1), segment(1, 50, 50, 2)),
            150,
            150,
            182
        )
        val changed = acceptedManifest(
            listOf(segment(0, 100, 100, 1), segment(1, 50, 50, 3)),
            150,
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
            150,
            182,
            listOf(segment(0, 100, 100, 1), segment(1, 50, 50, 2)),
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
        assertEquals(16, SEGMENT_AUTHENTICATION_TAG_SIZE_BYTES)
        assertNotEquals(
            LargeProtectedModelPayloadProfile.SEGMENTED_AES_256_GCM_SHA256_V1.id.value,
            ProtectedModelProfileId("direct-v0.1").toString()
        )
    }

    private fun acceptedManifest(
        drafts: List<LargeProtectedModelSegmentDraft>,
        totalPlaintext: Long,
        totalCiphertextBody: Long,
        totalProtectedPayload: Long
    ): LargeProtectedModelManifest = assertIs<LargeProtectedModelManifestResult.Accepted>(
        LargeProtectedModelManifestFactory.create(
            request(
                totalPlaintext,
                totalCiphertextBody,
                totalProtectedPayload,
                drafts,
                drafts.size
            ),
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
        totalCiphertextBody: Long,
        totalProtectedPayload: Long,
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
        totalCiphertextBodySizeBytes = totalCiphertextBody,
        totalProtectedPayloadSizeBytes = totalProtectedPayload,
        declaredSegmentCount = declaredCount,
        segments = drafts
    )

    private fun segment(
        index: Int,
        plaintext: Long,
        ciphertextBody: Long,
        nonceSeed: Int
    ) = LargeProtectedModelSegmentDraft(
        index = index,
        plaintextSizeBytes = plaintext,
        ciphertextBodySizeBytes = ciphertextBody,
        nonce = ByteArray(SEGMENT_NONCE_SIZE_BYTES) { (nonceSeed + it).toByte() },
        protectedPayloadDigest = ByteArray(SEGMENT_PROTECTED_PAYLOAD_DIGEST_SIZE_BYTES) {
            (nonceSeed * 3 + it).toByte()
        }
    )

    private fun budgets(
        maxSegments: Int = 1,
        maxTotal: Long = 200,
        maxSegment: Long = 120
    ) = LargeProtectedModelResourceBudgets(
        maxTotalPlaintextBytes = maxTotal,
        maxTotalCiphertextBodyBytes = maxTotal,
        maxTotalProtectedPayloadBytes = maxTotal + maxSegments * SEGMENT_AUTHENTICATION_TAG_SIZE_BYTES,
        maxSegmentCount = maxSegments,
        minNonFinalSegmentPlaintextBytes = 1,
        maxSegmentPlaintextBytes = maxSegment,
        maxSegmentCiphertextBodyBytes = maxSegment
    )
}
