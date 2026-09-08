package pro.liliya.android.runtime

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import pro.liliya.core.protectedmodel.*

class ProductProtectedModelLocalPackageContractTest {
    @Test
    fun valid_container_opens_exact_envelope_and_reads_one_segment_at_a_time() {
        val fixture = fixture()
        val file = writeContainer(fixture)
        try {
            val opened = assertIs<ProductProtectedModelLocalPackageOpenResult.Opened>(
                ProductProtectedModelLocalPackage.open(
                    file, budgets(), packageBudgets(), containerBudgets()
                )
            )
            assertEquals(fixture.manifest.model, opened.envelope.manifest.payload.model)
            assertEquals(2, opened.source.segmentCount)
            val segment = assertIs<LargeProtectedModelSegmentReadResult.Segment>(
                opened.source.read(1)
            ).value
            assertEquals(1, segment.index)
            assertContentEquals(fixture.bodies[1], segment.copyCiphertextBody())
            assertContentEquals(fixture.tags[1], segment.copyAuthenticationTag())
            assertTrue(!opened.source.toString().contains(file.absolutePath))
        } finally {
            file.delete()
        }
    }

    @Test
    fun trailing_data_and_segment_length_mismatch_fail_closed() {
        val fixture = fixture()
        val trailing = writeContainer(fixture, trailing = true)
        val mismatched = writeContainer(fixture, firstBodyLengthOverride = 6L)
        try {
            assertEquals(
                ProductProtectedModelLocalPackageFailure.TRAILING_DATA_REJECTED,
                assertIs<ProductProtectedModelLocalPackageOpenResult.Rejected>(
                    ProductProtectedModelLocalPackage.open(
                        trailing, budgets(), packageBudgets(), containerBudgets()
                    )
                ).reason
            )
            assertEquals(
                ProductProtectedModelLocalPackageFailure.SEGMENT_STRUCTURE_REJECTED,
                assertIs<ProductProtectedModelLocalPackageOpenResult.Rejected>(
                    ProductProtectedModelLocalPackage.open(
                        mismatched, budgets(), packageBudgets(), containerBudgets()
                    )
                ).reason
            )
        } finally {
            trailing.delete()
            mismatched.delete()
        }
    }

    @Test
    fun source_rejects_file_mutation_after_open() {
        val fixture = fixture()
        val file = writeContainer(fixture)
        try {
            val opened = assertIs<ProductProtectedModelLocalPackageOpenResult.Opened>(
                ProductProtectedModelLocalPackage.open(
                    file, budgets(), packageBudgets(), containerBudgets()
                )
            )
            file.appendBytes(byteArrayOf(9))
            assertIs<LargeProtectedModelSegmentReadResult.Rejected>(opened.source.read(0))
        } finally {
            file.delete()
        }
    }

    @Test
    fun unsupported_container_version_and_oversize_are_bounded() {
        val fixture = fixture()
        val unsupported = writeContainer(fixture, version = 2)
        val valid = writeContainer(fixture)
        try {
            assertEquals(
                ProductProtectedModelLocalPackageFailure.UNSUPPORTED_VERSION,
                assertIs<ProductProtectedModelLocalPackageOpenResult.Rejected>(
                    ProductProtectedModelLocalPackage.open(
                        unsupported, budgets(), packageBudgets(), containerBudgets()
                    )
                ).reason
            )
            assertEquals(
                ProductProtectedModelLocalPackageFailure.RESOURCE_LIMIT_REJECTED,
                assertIs<ProductProtectedModelLocalPackageOpenResult.Rejected>(
                    ProductProtectedModelLocalPackage.open(
                        valid,
                        budgets(),
                        packageBudgets(),
                        ProductProtectedModelLocalPackageBudgets(
                            maxContainerBytes = valid.length() - 1L,
                            maxSignatureBytes = 256
                        )
                    )
                ).reason
            )
        } finally {
            unsupported.delete()
            valid.delete()
        }
    }

    private data class Fixture(
        val manifest: LargeProtectedModelManifest,
        val signed: LargeProtectedModelSignedManifest,
        val signature: ByteArray,
        val bodies: List<ByteArray>,
        val tags: List<ByteArray>
    )

    private fun fixture(): Fixture {
        val bodies = listOf("alpha".encodeToByteArray(), "omega".encodeToByteArray())
        val tags = listOf(ByteArray(16) { 4 }, ByteArray(16) { 8 })
        val segments = bodies.indices.map { index ->
            val protected = bodies[index] + tags[index]
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(protected)
            LargeProtectedModelSegmentDraft(
                index = index,
                plaintextSizeBytes = bodies[index].size.toLong(),
                ciphertextBodySizeBytes = bodies[index].size.toLong(),
                nonce = ByteArray(12) { (index * 20 + it + 1).toByte() },
                protectedPayloadDigest = digest
            )
        }
        val manifest = assertIs<LargeProtectedModelManifestResult.Accepted>(
            LargeProtectedModelManifestFactory.create(
                LargeProtectedModelManifestRequest(
                    profile = LargeProtectedModelPayloadProfile.SEGMENTED_AES_256_GCM_SHA256_V1,
                    model = ProtectedModelReference(
                        ProtectedModelPackageId("local-import-model"),
                        ProtectedModelGeneration(3)
                    ),
                    modelDek = ModelDekReference(
                        ModelDekId("local-import-dek"),
                        ModelDekGeneration(2)
                    ),
                    totalPlaintextSizeBytes = 10,
                    totalCiphertextBodySizeBytes = 10,
                    totalProtectedPayloadSizeBytes = 42,
                    declaredSegmentCount = 2,
                    segments = segments
                ),
                budgets()
            )
        ).manifest
        return Fixture(
            manifest = manifest,
            signed = LargeProtectedModelSignedManifest(
                formatVersion = ProtectedModelFormatVersion(1),
                modelProfileId = ProtectedModelProfileId("gguf-product-v1"),
                payload = manifest,
                encryptionProfile = ProtectedModelEncryptionProfile.AES_256_GCM,
                signatureAlgorithm = ProtectedModelSignatureAlgorithm.ED25519,
                signerId = ProtectedModelSignerId("trusted-signer")
            ),
            signature = ByteArray(64) { 11 },
            bodies = bodies,
            tags = tags
        )
    }

    private fun writeContainer(
        fixture: Fixture,
        version: Int = 1,
        trailing: Boolean = false,
        firstBodyLengthOverride: Long? = null
    ): File {
        val file = File.createTempFile("liliya-local-package-", ".lpm")
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeInt(0x4C504D31)
            out.writeInt(version)
            writeBytes(out, signedManifestBytes(fixture.signed))
            writeBytes(out, fixture.signature)
            out.writeInt(fixture.manifest.segmentCount)
            fixture.manifest.segments().forEach { segment ->
                out.writeInt(segment.index)
                out.writeLong(
                    if (segment.index == 0 && firstBodyLengthOverride != null) {
                        firstBodyLengthOverride
                    } else {
                        fixture.bodies[segment.index].size.toLong()
                    }
                )
                out.writeInt(16)
                out.write(fixture.bodies[segment.index])
                out.write(fixture.tags[segment.index])
            }
            if (trailing) out.writeByte(1)
        }
        return file
    }

    private fun signedManifestBytes(manifest: LargeProtectedModelSignedManifest): ByteArray {
        val payload = LargeProtectedModelManifestCanonicalCodec.encode(manifest.payload)
        return try {
            ByteArrayOutputStream().also { buffer ->
                DataOutputStream(buffer).use { out ->
                    out.writeInt(1)
                    out.writeInt(manifest.formatVersion.value)
                    writeString(out, manifest.modelProfileId.value)
                    writeBytes(out, payload)
                    writeString(out, manifest.encryptionProfile.algorithm.name)
                    out.writeInt(manifest.encryptionProfile.keySizeBits)
                    out.writeInt(manifest.encryptionProfile.nonceSizeBytes)
                    out.writeInt(manifest.encryptionProfile.authenticationTagSizeBits)
                    writeString(out, manifest.signatureAlgorithm.name)
                    writeString(out, manifest.signerId.value)
                }
            }.toByteArray()
        } finally {
            payload.fill(0)
        }
    }

    private fun writeString(out: DataOutputStream, value: String) =
        writeBytes(out, value.encodeToByteArray())

    private fun writeBytes(out: DataOutputStream, value: ByteArray) {
        out.writeInt(value.size)
        out.write(value)
    }

    private fun budgets() = LargeProtectedModelResourceBudgets(
        maxTotalPlaintextBytes = 1024,
        maxTotalCiphertextBodyBytes = 1024,
        maxTotalProtectedPayloadBytes = 2048,
        maxSegmentCount = 8,
        minNonFinalSegmentPlaintextBytes = 1,
        maxSegmentPlaintextBytes = 512,
        maxSegmentCiphertextBodyBytes = 512,
        maxStructuralIdentifierChars = 128,
        maxCanonicalManifestBytes = 64 * 1024L
    )

    private fun packageBudgets() = LargeProtectedModelPackageBudgets(
        maxModelProfileIdChars = 128,
        maxSignerIdChars = 128,
        maxCanonicalSignedManifestBytes = 128 * 1024L
    )

    private fun containerBudgets() = ProductProtectedModelLocalPackageBudgets(
        maxContainerBytes = 1024 * 1024L,
        maxSignatureBytes = 256
    )
}
