package pro.liliya.core.protectedmodel

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LargeProtectedModelSegmentedCryptoContractTest {
    @Test
    fun signed_package_and_two_segments_complete_in_exact_order() {
        val fixture = fixture(listOf("alpha".encodeToByteArray(), "omega".encodeToByteArray()))
        val seen = mutableListOf<Pair<Int, ByteArray>>()

        val result = loader(fixture).open(
            fixture.envelope,
            source(fixture.encryptedSegments),
            LargeProtectedModelPlaintextSegmentConsumer { _, index, plaintext ->
                seen += index to plaintext.copyOf()
            }
        )

        val completed = assertIs<LargeProtectedModelSegmentedOpenResult.Completed>(result)
        assertEquals(listOf(0, 1), seen.map { it.first })
        assertContentEquals("alpha".encodeToByteArray(), seen[0].second)
        assertContentEquals("omega".encodeToByteArray(), seen[1].second)
        assertEquals(10L, completed.plaintextBytes)
    }

    @Test
    fun package_signature_and_signer_trust_are_mandatory() {
        val fixture = fixture(listOf("alpha".encodeToByteArray()))
        val badSignature = fixture.envelope.copySignature().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val invalid = LargeProtectedModelPackageEnvelope(fixture.signedManifest, badSignature)
        val invalidResult = loader(fixture).open(invalid, source(fixture.encryptedSegments), noopConsumer())
        assertEquals(
            LargeProtectedModelSegmentedOpenFailure.PACKAGE_SIGNATURE_INVALID,
            assertIs<LargeProtectedModelSegmentedOpenResult.Rejected>(invalidResult).reason
        )

        val unavailableVerifier = LargeProtectedModelPackageVerifier(
            ProtectedModelSignerResolver { _, _ -> null },
            packageBudgets()
        )
        val unavailable = LargeProtectedModelSegmentedPayloadLoader(unavailableVerifier, fixture.dekResolver)
            .open(fixture.envelope, source(fixture.encryptedSegments), noopConsumer())
        assertEquals(
            LargeProtectedModelSegmentedOpenFailure.SIGNER_UNAVAILABLE,
            assertIs<LargeProtectedModelSegmentedOpenResult.Rejected>(unavailable).reason
        )
    }

    @Test
    fun package_budget_is_explicit_and_enforced() {
        val fixture = fixture(listOf("alpha".encodeToByteArray()))
        val verifier = LargeProtectedModelPackageVerifier(
            ProtectedModelSignerResolver { _, _ -> fixture.keyPair.public },
            LargeProtectedModelPackageBudgets(2, 2, 16)
        )
        val result = verifier.verify(fixture.envelope)
        assertIs<LargeProtectedModelPackageVerificationResult.Rejected>(result)
    }

    @Test
    fun source_count_missing_and_index_substitution_fail_closed() {
        val fixture = fixture(listOf("alpha".encodeToByteArray(), "omega".encodeToByteArray()))
        val countMismatch = object : LargeProtectedModelEncryptedSegmentSource {
            override val segmentCount: Int = 1
            override fun read(index: Int) = LargeProtectedModelSegmentReadResult.Missing
        }
        assertReason(fixture, countMismatch, LargeProtectedModelSegmentedOpenFailure.SOURCE_SEGMENT_COUNT_MISMATCH)

        val missing = object : LargeProtectedModelEncryptedSegmentSource {
            override val segmentCount: Int = 2
            override fun read(index: Int) = if (index == 0) {
                LargeProtectedModelSegmentReadResult.Segment(fixture.encryptedSegments[0])
            } else {
                LargeProtectedModelSegmentReadResult.Missing
            }
        }
        assertReason(fixture, missing, LargeProtectedModelSegmentedOpenFailure.SEGMENT_MISSING)

        val original = fixture.encryptedSegments[0]
        val wrongIndex = LargeProtectedModelEncryptedSegment(
            index = 1,
            ciphertextBody = original.copyCiphertextBody(),
            authenticationTag = original.copyAuthenticationTag()
        )
        assertReason(
            fixture,
            source(listOf(wrongIndex, fixture.encryptedSegments[1])),
            LargeProtectedModelSegmentedOpenFailure.SEGMENT_INDEX_MISMATCH
        )
    }

    @Test
    fun digest_mismatch_rejects_before_plaintext_consumer() {
        val fixture = fixture(listOf("alpha".encodeToByteArray()))
        val original = fixture.encryptedSegments.single()
        val tamperedBody = original.copyCiphertextBody().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val tampered = LargeProtectedModelEncryptedSegment(
            original.index,
            tamperedBody,
            original.copyAuthenticationTag()
        )
        var called = false
        val result = loader(fixture).open(
            fixture.envelope,
            source(listOf(tampered)),
            LargeProtectedModelPlaintextSegmentConsumer { _, _, _ -> called = true }
        )
        assertFalse(called)
        assertEquals(
            LargeProtectedModelSegmentedOpenFailure.PROTECTED_PAYLOAD_DIGEST_MISMATCH,
            assertIs<LargeProtectedModelSegmentedOpenResult.Rejected>(result).reason
        )
    }

    @Test
    fun valid_signed_digest_with_invalid_gcm_tag_reaches_authenticated_decrypt_failure() {
        val fixture = fixture(listOf("alpha".encodeToByteArray()))
        val original = fixture.encryptedSegments.single()
        val badTag = original.copyAuthenticationTag().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val alteredEncrypted = LargeProtectedModelEncryptedSegment(0, original.copyCiphertextBody(), badTag)
        val rebuilt = resignForEncrypted(fixture, listOf(alteredEncrypted))

        val result = loader(rebuilt).open(rebuilt.envelope, source(rebuilt.encryptedSegments), noopConsumer())
        assertEquals(
            LargeProtectedModelSegmentedOpenFailure.AUTHENTICATED_DECRYPTION_FAILED,
            assertIs<LargeProtectedModelSegmentedOpenResult.Rejected>(result).reason
        )
    }

    @Test
    fun aad_binds_exact_model_generation_even_when_package_is_resigned() {
        val fixture = fixture(listOf("alpha".encodeToByteArray()))
        val changedModel = ProtectedModelReference(
            fixture.payload.model.packageId,
            ProtectedModelGeneration(fixture.payload.model.generation.value + 1)
        )
        val rebuiltPayload = rebuildPayload(
            fixture.payload,
            model = changedModel,
            encryptedSegments = fixture.encryptedSegments
        )
        val resigned = signFixture(fixture, rebuiltPayload, fixture.encryptedSegments)

        val result = loader(resigned).open(resigned.envelope, source(resigned.encryptedSegments), noopConsumer())
        assertEquals(
            LargeProtectedModelSegmentedOpenFailure.AUTHENTICATED_DECRYPTION_FAILED,
            assertIs<LargeProtectedModelSegmentedOpenResult.Rejected>(result).reason
        )
    }

    @Test
    fun exact_model_dek_resolution_and_aes256_shape_are_required() {
        val fixture = fixture(listOf("alpha".encodeToByteArray()))
        var requestedModel: ProtectedModelReference? = null
        var requestedDek: ModelDekReference? = null
        val resolver = ProtectedModelDekResolver { model, dek ->
            requestedModel = model
            requestedDek = dek
            fixture.secretKey
        }
        val verifier = LargeProtectedModelPackageVerifier(
            ProtectedModelSignerResolver { _, _ -> fixture.keyPair.public }, packageBudgets()
        )
        val result = LargeProtectedModelSegmentedPayloadLoader(verifier, resolver)
            .open(fixture.envelope, source(fixture.encryptedSegments), noopConsumer())
        assertIs<LargeProtectedModelSegmentedOpenResult.Completed>(result)
        assertEquals(fixture.payload.model, requestedModel)
        assertEquals(fixture.payload.modelDek, requestedDek)

        val wrongKeyResolver = ProtectedModelDekResolver { _, _ -> SecretKeySpec(ByteArray(16), "AES") }
        val rejected = LargeProtectedModelSegmentedPayloadLoader(verifier, wrongKeyResolver)
            .open(fixture.envelope, source(fixture.encryptedSegments), noopConsumer())
        assertEquals(
            LargeProtectedModelSegmentedOpenFailure.MODEL_DEK_REJECTED,
            assertIs<LargeProtectedModelSegmentedOpenResult.Rejected>(rejected).reason
        )
    }

    @Test
    fun consumer_runs_only_after_authentication_and_plaintext_array_is_cleared_after_return() {
        val fixture = fixture(listOf("secret-alpha".encodeToByteArray()))
        var handed: ByteArray? = null
        val result = loader(fixture).open(
            fixture.envelope,
            source(fixture.encryptedSegments),
            LargeProtectedModelPlaintextSegmentConsumer { _, _, plaintext ->
                assertContentEquals("secret-alpha".encodeToByteArray(), plaintext)
                handed = plaintext
            }
        )
        assertIs<LargeProtectedModelSegmentedOpenResult.Completed>(result)
        assertTrue(handed!!.all { it == 0.toByte() })
    }

    @Test
    fun later_failure_does_not_claim_rollback_of_earlier_consumer_side_effects() {
        val fixture = fixture(listOf("alpha".encodeToByteArray(), "omega".encodeToByteArray()))
        val second = fixture.encryptedSegments[1]
        val badSecond = LargeProtectedModelEncryptedSegment(
            1,
            second.copyCiphertextBody().also { it[0] = (it[0].toInt() xor 1).toByte() },
            second.copyAuthenticationTag()
        )
        val seen = mutableListOf<Int>()
        val result = loader(fixture).open(
            fixture.envelope,
            source(listOf(fixture.encryptedSegments[0], badSecond)),
            LargeProtectedModelPlaintextSegmentConsumer { _, index, _ -> seen += index }
        )
        assertEquals(listOf(0), seen)
        assertEquals(
            LargeProtectedModelSegmentedOpenFailure.PROTECTED_PAYLOAD_DIGEST_MISMATCH,
            assertIs<LargeProtectedModelSegmentedOpenResult.Rejected>(result).reason
        )
    }

    @Test
    fun mutable_encrypted_inputs_and_signature_are_defensively_detached_and_rendering_is_redacted() {
        val fixture = fixture(listOf("alpha".encodeToByteArray()))
        val original = fixture.encryptedSegments.single()
        val body = original.copyCiphertextBody()
        val tag = original.copyAuthenticationTag()
        val detached = LargeProtectedModelEncryptedSegment(0, body, tag)
        body.fill(0)
        tag.fill(0)
        assertFalse(detached.copyCiphertextBody().all { it == 0.toByte() })
        assertFalse(detached.copyAuthenticationTag().all { it == 0.toByte() })
        assertTrue(detached.toString().contains("<redacted:"))
        assertTrue(fixture.envelope.toString().contains("signature=<redacted:"))
    }

    @Test
    fun direct_protected_model_v0_1_types_remain_usable() {
        val profile = ProtectedModelEncryptionProfile.AES_256_GCM
        assertEquals(12, profile.nonceSizeBytes)
        assertEquals(128, profile.authenticationTagSizeBits)
    }

    private fun assertReason(
        fixture: Fixture,
        source: LargeProtectedModelEncryptedSegmentSource,
        expected: LargeProtectedModelSegmentedOpenFailure
    ) {
        val result = loader(fixture).open(fixture.envelope, source, noopConsumer())
        assertEquals(expected, assertIs<LargeProtectedModelSegmentedOpenResult.Rejected>(result).reason)
    }

    private fun noopConsumer() = LargeProtectedModelPlaintextSegmentConsumer { _, _, _ -> }

    private fun loader(fixture: Fixture): LargeProtectedModelSegmentedPayloadLoader {
        val verifier = LargeProtectedModelPackageVerifier(
            ProtectedModelSignerResolver { signerId, algorithm ->
                if (signerId == fixture.signedManifest.signerId && algorithm == ProtectedModelSignatureAlgorithm.ED25519) {
                    fixture.keyPair.public
                } else null
            },
            packageBudgets()
        )
        return LargeProtectedModelSegmentedPayloadLoader(verifier, fixture.dekResolver)
    }

    private fun packageBudgets() = LargeProtectedModelPackageBudgets(
        maxModelProfileIdChars = 64,
        maxSignerIdChars = 64,
        maxCanonicalSignedManifestBytes = 64 * 1024L
    )

    private fun source(segments: List<LargeProtectedModelEncryptedSegment>) =
        object : LargeProtectedModelEncryptedSegmentSource {
            override val segmentCount: Int = segments.size
            override fun read(index: Int): LargeProtectedModelSegmentReadResult =
                segments.getOrNull(index)?.let { LargeProtectedModelSegmentReadResult.Segment(it) }
                    ?: LargeProtectedModelSegmentReadResult.Missing
        }

    private data class Fixture(
        val payload: LargeProtectedModelManifest,
        val signedManifest: LargeProtectedModelSignedManifest,
        val envelope: LargeProtectedModelPackageEnvelope,
        val encryptedSegments: List<LargeProtectedModelEncryptedSegment>,
        val secretKey: SecretKeySpec,
        val keyPair: KeyPair,
        val dekResolver: ProtectedModelDekResolver
    )

    private fun fixture(plaintexts: List<ByteArray>): Fixture {
        val secretKey = SecretKeySpec(ByteArray(32) { (it + 11).toByte() }, "AES")
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val model = ProtectedModelReference(
            ProtectedModelPackageId("large-package"),
            ProtectedModelGeneration(7)
        )
        val dek = ModelDekReference(ModelDekId("large-dek"), ModelDekGeneration(9))
        val nonces = plaintexts.indices.map { index ->
            ByteArray(SEGMENT_NONCE_SIZE_BYTES) { offset -> (index * 17 + offset + 1).toByte() }
        }

        val dummyPayload = createPayload(
            model,
            dek,
            plaintexts,
            nonces,
            List(plaintexts.size) { ByteArray(SEGMENT_PROTECTED_PAYLOAD_DIGEST_SIZE_BYTES) }
        )
        val dummySigned = signedManifest(dummyPayload)
        val encrypted = plaintexts.indices.map { index ->
            encrypt(dummySigned, dummyPayload.segments()[index], plaintexts[index], nonces[index], secretKey)
        }
        val digests = encrypted.map { protectedDigest(it) }
        val payload = createPayload(model, dek, plaintexts, nonces, digests)
        return signFixture(
            Fixture(
                payload,
                signedManifest(payload),
                LargeProtectedModelPackageEnvelope(signedManifest(payload), byteArrayOf(1)),
                encrypted,
                secretKey,
                keyPair,
                ProtectedModelDekResolver { expectedModel, expectedDek ->
                    if (expectedModel == model && expectedDek == dek) secretKey else null
                }
            ),
            payload,
            encrypted
        )
    }

    private fun signFixture(
        base: Fixture,
        payload: LargeProtectedModelManifest,
        encryptedSegments: List<LargeProtectedModelEncryptedSegment>
    ): Fixture {
        val signed = signedManifest(payload)
        val input = LargeProtectedModelPackageCanonicalCodec.signatureInput(signed)
        val signature = try {
            Signature.getInstance("Ed25519").run {
                initSign(base.keyPair.private)
                update(input)
                sign()
            }
        } finally {
            input.fill(0)
        }
        return base.copy(
            payload = payload,
            signedManifest = signed,
            envelope = LargeProtectedModelPackageEnvelope(signed, signature),
            encryptedSegments = encryptedSegments
        )
    }

    private fun resignForEncrypted(
        fixture: Fixture,
        encryptedSegments: List<LargeProtectedModelEncryptedSegment>
    ): Fixture {
        val payload = rebuildPayload(fixture.payload, encryptedSegments = encryptedSegments)
        return signFixture(fixture, payload, encryptedSegments)
    }

    private fun rebuildPayload(
        original: LargeProtectedModelManifest,
        model: ProtectedModelReference = original.model,
        encryptedSegments: List<LargeProtectedModelEncryptedSegment>
    ): LargeProtectedModelManifest {
        val plaintextSizes = original.segments().map { ByteArray(it.plaintextSizeBytes.toInt()) }
        val nonces = original.segments().map { it.copyNonce() }
        val digests = encryptedSegments.map { protectedDigest(it) }
        return createPayload(model, original.modelDek, plaintextSizes, nonces, digests)
    }

    private fun createPayload(
        model: ProtectedModelReference,
        dek: ModelDekReference,
        plaintexts: List<ByteArray>,
        nonces: List<ByteArray>,
        digests: List<ByteArray>
    ): LargeProtectedModelManifest {
        val drafts = plaintexts.indices.map { index ->
            LargeProtectedModelSegmentDraft(
                index = index,
                plaintextSizeBytes = plaintexts[index].size.toLong(),
                ciphertextBodySizeBytes = plaintexts[index].size.toLong(),
                nonce = nonces[index],
                protectedPayloadDigest = digests[index]
            )
        }
        val plaintextTotal = plaintexts.sumOf { it.size.toLong() }
        val protectedTotal = plaintextTotal + plaintexts.size.toLong() * SEGMENT_AUTHENTICATION_TAG_SIZE_BYTES
        val request = LargeProtectedModelManifestRequest(
            profile = LargeProtectedModelPayloadProfile.SEGMENTED_AES_256_GCM_SHA256_V1,
            model = model,
            modelDek = dek,
            totalPlaintextSizeBytes = plaintextTotal,
            totalCiphertextBodySizeBytes = plaintextTotal,
            totalProtectedPayloadSizeBytes = protectedTotal,
            declaredSegmentCount = drafts.size,
            segments = drafts
        )
        val budgets = LargeProtectedModelResourceBudgets(
            maxTotalPlaintextBytes = 4096,
            maxTotalCiphertextBodyBytes = 4096,
            maxTotalProtectedPayloadBytes = 8192,
            maxSegmentCount = 16,
            minNonFinalSegmentPlaintextBytes = 1,
            maxSegmentPlaintextBytes = 1024,
            maxSegmentCiphertextBodyBytes = 1024,
            maxStructuralIdentifierChars = 128,
            maxCanonicalManifestBytes = 64 * 1024
        )
        return assertIs<LargeProtectedModelManifestResult.Accepted>(
            LargeProtectedModelManifestFactory.create(request, budgets)
        ).manifest
    }

    private fun signedManifest(payload: LargeProtectedModelManifest) = LargeProtectedModelSignedManifest(
        formatVersion = ProtectedModelFormatVersion(1),
        modelProfileId = ProtectedModelProfileId("gguf-v1"),
        payload = payload,
        encryptionProfile = ProtectedModelEncryptionProfile.AES_256_GCM,
        signatureAlgorithm = ProtectedModelSignatureAlgorithm.ED25519,
        signerId = ProtectedModelSignerId("release-signer")
    )

    private fun encrypt(
        manifest: LargeProtectedModelSignedManifest,
        segment: LargeProtectedModelSegment,
        plaintext: ByteArray,
        nonce: ByteArray,
        key: SecretKeySpec
    ): LargeProtectedModelEncryptedSegment {
        val aad = LargeProtectedModelSegmentAadCodec.encode(manifest, segment)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        val protected = cipher.doFinal(plaintext)
        aad.fill(0)
        return try {
            val bodySize = protected.size - SEGMENT_AUTHENTICATION_TAG_SIZE_BYTES
            LargeProtectedModelEncryptedSegment(
                segment.index,
                protected.copyOfRange(0, bodySize),
                protected.copyOfRange(bodySize, protected.size)
            )
        } finally {
            protected.fill(0)
        }
    }

    private fun protectedDigest(segment: LargeProtectedModelEncryptedSegment): ByteArray {
        val body = segment.copyCiphertextBody()
        val tag = segment.copyAuthenticationTag()
        return try {
            MessageDigest.getInstance("SHA-256").run {
                update(body)
                digest(tag)
            }
        } finally {
            body.fill(0)
            tag.fill(0)
        }
    }
}
