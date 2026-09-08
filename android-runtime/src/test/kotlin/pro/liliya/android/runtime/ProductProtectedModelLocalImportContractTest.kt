package pro.liliya.android.runtime

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.protectedmodel.LargeProtectedModelCanonicalDecodeResult
import pro.liliya.core.protectedmodel.LargeProtectedModelEncryptedSegment
import pro.liliya.core.protectedmodel.LargeProtectedModelManifest
import pro.liliya.core.protectedmodel.LargeProtectedModelManifestFactory
import pro.liliya.core.protectedmodel.LargeProtectedModelManifestRequest
import pro.liliya.core.protectedmodel.LargeProtectedModelManifestResult
import pro.liliya.core.protectedmodel.LargeProtectedModelPackageEnvelope
import pro.liliya.core.protectedmodel.LargeProtectedModelPayloadProfile
import pro.liliya.core.protectedmodel.LargeProtectedModelResourceBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelSegmentDraft
import pro.liliya.core.protectedmodel.LargeProtectedModelSegmentReadResult
import pro.liliya.core.protectedmodel.LargeProtectedModelSignedManifest
import pro.liliya.core.protectedmodel.ModelDekGeneration
import pro.liliya.core.protectedmodel.ModelDekId
import pro.liliya.core.protectedmodel.ModelDekReference
import pro.liliya.core.protectedmodel.ProtectedModelEncryptionProfile
import pro.liliya.core.protectedmodel.ProtectedModelFormatVersion
import pro.liliya.core.protectedmodel.ProtectedModelGeneration
import pro.liliya.core.protectedmodel.ProtectedModelPackageId
import pro.liliya.core.protectedmodel.ProtectedModelProfileId
import pro.liliya.core.protectedmodel.ProtectedModelReference
import pro.liliya.core.protectedmodel.ProtectedModelSignatureAlgorithm
import pro.liliya.core.protectedmodel.ProtectedModelSignerId

class ProductProtectedModelLocalImportContractTest {
    @Test
    fun valid_container_reconstructs_envelope_and_reads_exact_segments_on_demand() {
        val fixture = fixture()
        val file = writeContainer(
            manifestBytes = byteArrayOf(7, 8, 9),
            signature = byteArrayOf(1, 2, 3),
            bodies = listOf(byteArrayOf(10, 11, 12, 13), byteArrayOf(20, 21, 22, 23)),
            tags = listOf(ByteArray(16) { 31 }, ByteArray(16) { 41 })
        )

        val result = importer(fixture.manifest).open(file)
        val ready = assertIs<ProductProtectedModelLocalImportResult.Ready>(result)
        assertEquals(fixture.manifest, ready.envelope.manifest)
        assertEquals(2, ready.source.segmentCount)

        val first = assertIs<LargeProtectedModelSegmentReadResult.Segment>(
            ready.source.read(0)
        ).value
        assertContentEquals(byteArrayOf(10, 11, 12, 13), first.copyCiphertextBody())
        assertContentEquals(ByteArray(16) { 31 }, first.copyAuthenticationTag())

        val second = assertIs<LargeProtectedModelSegmentReadResult.Segment>(
            ready.source.read(1)
        ).value
        assertContentEquals(byteArrayOf(20, 21, 22, 23), second.copyCiphertextBody())
        assertEquals(LargeProtectedModelSegmentReadResult.Missing, ready.source.read(2))
        assertTrue(ready.source.toString().contains("file=<redacted>"))
        assertTrue(!ready.source.toString().contains(file.absolutePath))
    }

    @Test
    fun trailing_data_rejects_fail_closed() {
        val fixture = fixture()
        val file = writeContainer(
            manifestBytes = byteArrayOf(7),
            signature = byteArrayOf(1),
            bodies = listOf(ByteArray(4), ByteArray(4)),
            tags = listOf(ByteArray(16), ByteArray(16)),
            trailing = byteArrayOf(99)
        )

        val rejected = assertIs<ProductProtectedModelLocalImportResult.Rejected>(
            importer(fixture.manifest).open(file)
        )
        assertEquals(ProductProtectedModelLocalImportFailure.TRAILING_DATA, rejected.reason)
    }

    @Test
    fun oversized_manifest_framing_rejects_before_decoder_call() {
        val fixture = fixture()
        var decodeCalls = 0
        val importer = ProductProtectedModelLocalImport(
            decodePort = ProductProtectedModelSignedManifestDecodePort {
                decodeCalls += 1
                LargeProtectedModelCanonicalDecodeResult.Decoded(fixture.manifest)
            },
            budgets = ProductProtectedModelLocalImportBudgets(
                maxContainerBytes = 1024 * 1024,
                maxSignatureBytes = 1024
            ),
            maxCanonicalSignedManifestBytes = 2
        )
        val file = writeContainer(
            manifestBytes = byteArrayOf(7, 8, 9),
            signature = byteArrayOf(1),
            bodies = listOf(ByteArray(4), ByteArray(4)),
            tags = listOf(ByteArray(16), ByteArray(16))
        )

        val rejected = assertIs<ProductProtectedModelLocalImportResult.Rejected>(
            importer.open(file)
        )
        assertEquals(
            ProductProtectedModelLocalImportFailure.RESOURCE_LIMIT_REJECTED,
            rejected.reason
        )
        assertEquals(0, decodeCalls)
    }

    @Test
    fun file_mutation_after_open_invalidates_segment_source() {
        val fixture = fixture()
        val file = writeContainer(
            manifestBytes = byteArrayOf(7),
            signature = byteArrayOf(1),
            bodies = listOf(ByteArray(4), ByteArray(4)),
            tags = listOf(ByteArray(16), ByteArray(16))
        )
        val ready = assertIs<ProductProtectedModelLocalImportResult.Ready>(
            importer(fixture.manifest).open(file)
        )

        file.appendBytes(byteArrayOf(1))

        assertIs<LargeProtectedModelSegmentReadResult.Rejected>(ready.source.read(0))
    }

    @Test
    fun declared_segment_count_mismatch_rejects_before_source_publication() {
        val fixture = fixture()
        val file = tempFile()
        DataOutputStream(file.outputStream()).use { out ->
            out.writeInt(0x4C504D31)
            out.writeInt(1)
            writeBytes(out, byteArrayOf(7))
            writeBytes(out, byteArrayOf(1))
            out.writeInt(1)
            out.writeInt(0)
            out.writeInt(4)
            out.write(ByteArray(4))
            out.writeInt(16)
            out.write(ByteArray(16))
        }

        val rejected = assertIs<ProductProtectedModelLocalImportResult.Rejected>(
            importer(fixture.manifest).open(file)
        )
        assertEquals(
            ProductProtectedModelLocalImportFailure.SEGMENT_LAYOUT_MISMATCH,
            rejected.reason
        )
    }

    private fun importer(
        manifest: LargeProtectedModelSignedManifest
    ) = ProductProtectedModelLocalImport(
        decodePort = ProductProtectedModelSignedManifestDecodePort {
            LargeProtectedModelCanonicalDecodeResult.Decoded(manifest)
        },
        budgets = ProductProtectedModelLocalImportBudgets(
            maxContainerBytes = 1024 * 1024,
            maxSignatureBytes = 1024
        ),
        maxCanonicalSignedManifestBytes = 64 * 1024
    )

    private fun fixture(): Fixture {
        val request = LargeProtectedModelManifestRequest(
            profile = LargeProtectedModelPayloadProfile.SEGMENTED_AES_256_GCM_SHA256_V1,
            model = ProtectedModelReference(
                ProtectedModelPackageId("local-import"),
                ProtectedModelGeneration(1)
            ),
            modelDek = ModelDekReference(
                ModelDekId("local-import-dek"),
                ModelDekGeneration(1)
            ),
            totalPlaintextSizeBytes = 8,
            totalCiphertextBodySizeBytes = 8,
            totalProtectedPayloadSizeBytes = 40,
            declaredSegmentCount = 2,
            segments = listOf(
                LargeProtectedModelSegmentDraft(
                    0, 4, 4, ByteArray(12) { 1 }, ByteArray(32) { 2 }
                ),
                LargeProtectedModelSegmentDraft(
                    1, 4, 4, ByteArray(12) { 3 }, ByteArray(32) { 4 }
                )
            )
        )
        val payload = assertIs<LargeProtectedModelManifestResult.Accepted>(
            LargeProtectedModelManifestFactory.create(request, resourceBudgets())
        ).manifest
        return Fixture(
            LargeProtectedModelSignedManifest(
                formatVersion = ProtectedModelFormatVersion(1),
                modelProfileId = ProtectedModelProfileId("GGUF"),
                payload = payload,
                encryptionProfile = ProtectedModelEncryptionProfile.AES_256_GCM,
                signatureAlgorithm = ProtectedModelSignatureAlgorithm.ED25519,
                signerId = ProtectedModelSignerId("local-import-signer")
            )
        )
    }

    private fun writeContainer(
        manifestBytes: ByteArray,
        signature: ByteArray,
        bodies: List<ByteArray>,
        tags: List<ByteArray>,
        trailing: ByteArray = byteArrayOf()
    ): File {
        val file = tempFile()
        DataOutputStream(file.outputStream()).use { out ->
            out.writeInt(0x4C504D31)
            out.writeInt(1)
            writeBytes(out, manifestBytes)
            writeBytes(out, signature)
            out.writeInt(bodies.size)
            bodies.indices.forEach { index ->
                out.writeInt(index)
                out.writeInt(bodies[index].size)
                out.write(bodies[index])
                out.writeInt(tags[index].size)
                out.write(tags[index])
            }
            out.write(trailing)
        }
        return file
    }

    private fun tempFile(): File =
        createTempDirectory("liliya-local-import").resolve("model.lpm").toFile().apply {
            deleteOnExit()
        }

    private fun writeBytes(out: DataOutputStream, bytes: ByteArray) {
        out.writeInt(bytes.size)
        out.write(bytes)
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

    private data class Fixture(val manifest: LargeProtectedModelSignedManifest)
}
