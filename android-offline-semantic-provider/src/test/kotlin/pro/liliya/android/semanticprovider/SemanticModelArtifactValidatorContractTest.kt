package pro.liliya.android.semanticprovider

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SemanticModelArtifactValidatorContractTest {
    @Test
    fun exact_app_private_production_artifact_validates_against_separate_trusted_identity() {
        withRoot { root ->
            val bytes = "semantic-model-fixture".toByteArray()
            val file = File(root, "models/semantic.onnx").also {
                it.parentFile!!.mkdirs()
                it.writeBytes(bytes)
            }
            val spec = productionSpec(bytes, file.name)

            val result = validator(root, spec).validate(file, spec)
            val validated = assertIs<SemanticModelArtifactValidationResult.Validated>(result)

            assertEquals(file.canonicalFile, validated.artifact.file)
            assertEquals(spec, validated.artifact.spec)
            assertTrue(!validated.toString().contains(file.absolutePath))
            assertTrue(!validated.toString().contains(spec.expectedSha256))
            assertTrue(!spec.toString().contains(spec.expectedSha256))
        }
    }

    @Test
    fun candidate_cannot_self_authorize_a_different_artifact_identity() {
        withRoot { root ->
            val bytes = "semantic-model-fixture".toByteArray()
            val file = File(root, "semantic.onnx").also { it.writeBytes(bytes) }
            val trusted = productionSpec(bytes, file.name)
            val differentIdentity = trusted.identity.copy(
                conversionProvenance = SemanticConversionProvenance.Reproducible(
                    artifactRepository = "test/other-artifact",
                    artifactRevision = "other-artifact-revision",
                    conversionToolRevision = "other-conversion-tool-revision"
                )
            )

            assertIs<SemanticModelArtifactValidationResult.ArtifactIdentityMismatch>(
                validator(root, trusted).validate(
                    file,
                    SemanticModelArtifactSpec(differentIdentity)
                )
            )
        }
    }

    @Test
    fun benchmark_only_provenance_requires_explicit_non_production_acceptance() {
        withRoot { root ->
            val bytes = "benchmark-onnx-fixture".toByteArray()
            val identity = productionIdentity(
                bytes = bytes,
                fileName = SemanticModelProfileV01.ONNX_FILE_NAME,
                expectedSizeBytes = bytes.size.toLong() + 1L
            ).copy(
                conversionProvenance = SemanticConversionProvenance.ControlledBenchmark(
                    artifactRepository = "test/controlled-benchmark",
                    artifactRevision = "controlled-benchmark-revision"
                )
            )
            val file = File(root, identity.modelFileName).also { it.writeBytes(bytes) }
            val spec = SemanticModelArtifactSpec(identity)

            assertIs<SemanticModelArtifactValidationResult.IncompleteConversionProvenance>(
                SemanticModelArtifactValidator(root, identity).validate(file, spec)
            )
            assertIs<SemanticModelArtifactValidationResult.IncompleteConversionProvenance>(
                SemanticModelArtifactValidator(
                    root,
                    identity,
                    SemanticModelArtifactAcceptance.REPRODUCIBLE_CI_FIXTURE
                ).validate(file, spec)
            )

            assertIs<SemanticModelArtifactValidationResult.SizeMismatch>(
                SemanticModelArtifactValidator(
                    root,
                    identity,
                    SemanticModelArtifactAcceptance.CONTROLLED_BENCHMARK
                ).validate(file, spec)
            )
        }
    }

    @Test
    fun ephemeral_ci_provenance_cannot_be_accepted_as_published_production_provenance() {
        withRoot { root ->
            val bytes = "self-reproduced-ci-fixture".toByteArray()
            val file = File(root, "semantic-ci.onnx").also { it.writeBytes(bytes) }
            val identity = productionIdentity(bytes, file.name).copy(
                conversionProvenance = SemanticConversionProvenance.ReproducibleCiFixture(
                    conversionToolRevision = SemanticModelProfileV01.ONNX_EXPORT_PIPELINE_REVISION
                )
            )
            val spec = SemanticModelArtifactSpec(identity)

            assertIs<SemanticModelArtifactValidationResult.IncompleteConversionProvenance>(
                SemanticModelArtifactValidator(root, identity).validate(file, spec)
            )
            assertIs<SemanticModelArtifactValidationResult.Validated>(
                SemanticModelArtifactValidator(
                    root,
                    identity,
                    SemanticModelArtifactAcceptance.REPRODUCIBLE_CI_FIXTURE
                ).validate(file, spec)
            )
        }
    }

    @Test
    fun profile_and_file_name_mismatch_fail_before_file_validation() {
        withRoot { root ->
            val bytes = "semantic-model-fixture".toByteArray()
            val file = File(root, "semantic.onnx").also { it.writeBytes(bytes) }
            val trusted = productionSpec(bytes, file.name)
            val mismatchedProfile = SemanticModelArtifactSpec(
                trusted.identity.copy(profileId = "wrong-profile")
            )
            assertIs<SemanticModelArtifactValidationResult.ProfileMismatch>(
                validator(root, trusted).validate(file, mismatchedProfile)
            )

            val expectedName = productionSpec(bytes, "expected.onnx")
            assertIs<SemanticModelArtifactValidationResult.FileNameMismatch>(
                validator(root, expectedName).validate(file, expectedName)
            )
        }
    }

    @Test
    fun missing_directory_size_and_digest_fail_structurally() {
        withRoot { root ->
            val missing = File(root, "missing.onnx")
            val missingSpec = productionSpec("x".toByteArray(), missing.name)
            assertIs<SemanticModelArtifactValidationResult.Missing>(
                validator(root, missingSpec).validate(missing, missingSpec)
            )

            val directory = File(root, "directory").also { it.mkdirs() }
            val directorySpec = productionSpec("x".toByteArray(), directory.name)
            assertIs<SemanticModelArtifactValidationResult.NotRegularFile>(
                validator(root, directorySpec).validate(directory, directorySpec)
            )

            val file = File(root, "model.onnx").also { it.writeText("actual") }
            val expected = "expected".toByteArray()
            val sizeSpec = productionSpec(expected, file.name)
            assertIs<SemanticModelArtifactValidationResult.SizeMismatch>(
                validator(root, sizeSpec).validate(file, sizeSpec)
            )

            val sameSizeWrongDigest = "xxxxxx".toByteArray()
            assertEquals(file.length(), sameSizeWrongDigest.size.toLong())
            val digestSpec = productionSpec(sameSizeWrongDigest, file.name)
            assertIs<SemanticModelArtifactValidationResult.DigestMismatch>(
                validator(root, digestSpec).validate(file, digestSpec)
            )
        }
    }

    @Test
    fun artifact_over_profile_byte_limit_is_rejected_before_size_or_digest_validation() {
        withRoot { root ->
            val file = File(root, "semantic.onnx").also { it.writeBytes(byteArrayOf(0)) }
            val oversizedSpec = SemanticModelArtifactSpec(
                productionIdentity(
                    bytes = byteArrayOf(0),
                    fileName = file.name,
                    expectedSizeBytes = SemanticModelProfileV01.MAX_ARTIFACT_BYTES + 1L
                )
            )

            assertIs<SemanticModelArtifactValidationResult.ArtifactTooLarge>(
                validator(root, oversizedSpec).validate(file, oversizedSpec)
            )
        }
    }

    @Test
    fun outside_root_and_symlink_escape_are_rejected_before_hashing() {
        val root = Files.createTempDirectory("semantic-root").toFile()
        val outsideRoot = Files.createTempDirectory("semantic-outside").toFile()
        try {
            val outside = File(outsideRoot, "semantic.onnx").also { it.writeText("secret") }
            val outsideSpec = productionSpec("secret".toByteArray(), outside.name)
            val validator = validator(root, outsideSpec)

            assertIs<SemanticModelArtifactValidationResult.OutsideAppPrivateRoot>(
                validator.validate(outside, outsideSpec)
            )

            val link = File(root, "linked.onnx")
            try {
                Files.createSymbolicLink(link.toPath(), outside.toPath())
                val linkSpec = productionSpec("secret".toByteArray(), link.name)
                assertIs<SemanticModelArtifactValidationResult.OutsideAppPrivateRoot>(
                    validator(root, linkSpec).validate(link, linkSpec)
                )
            } catch (_: UnsupportedOperationException) {
                // Host filesystem does not support symbolic links; direct escape remains proven.
            }
        } finally {
            root.deleteRecursively()
            outsideRoot.deleteRecursively()
        }
    }

    @Test
    fun malformed_identity_is_rejected_before_validation() {
        try {
            productionIdentity(
                bytes = ByteArray(0),
                fileName = "semantic.onnx",
                expectedSizeBytes = 0L
            )
            error("expected size validation")
        } catch (_: IllegalArgumentException) {
        }
        try {
            productionIdentity(
                bytes = "x".toByteArray(),
                fileName = "semantic.onnx",
                expectedSha256 = "ABC"
            )
            error("expected digest validation")
        } catch (_: IllegalArgumentException) {
        }
        try {
            productionIdentity("x".toByteArray(), "../semantic.onnx")
            error("expected filename validation")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun validator(
        root: File,
        trusted: SemanticModelArtifactSpec
    ): SemanticModelArtifactValidator =
        SemanticModelArtifactValidator(root, trusted.identity)

    private fun productionSpec(
        bytes: ByteArray,
        fileName: String
    ): SemanticModelArtifactSpec =
        SemanticModelArtifactSpec(productionIdentity(bytes, fileName))

    private fun productionIdentity(
        bytes: ByteArray,
        fileName: String,
        expectedSizeBytes: Long = bytes.size.toLong(),
        expectedSha256: String = sha256(bytes)
    ): SemanticModelArtifactIdentity = SemanticModelArtifactIdentity(
        profileId = SemanticModelProfileV01.PROFILE_ID,
        profileGeneration = SemanticModelProfileV01.PROFILE_GENERATION,
        upstreamModelRepository = SemanticModelProfileV01.UPSTREAM_MODEL_REPOSITORY,
        upstreamModelRevision = SemanticModelProfileV01.UPSTREAM_MODEL_REVISION,
        conversionProvenance = SemanticConversionProvenance.Reproducible(
            artifactRepository = "test/reproducible-semantic-artifact",
            artifactRevision = "test-artifact-revision",
            conversionToolRevision = "test-conversion-tool-revision"
        ),
        modelFileName = fileName,
        modelFormat = SemanticModelFormat.ONNX,
        expectedSizeBytes = expectedSizeBytes,
        expectedSha256 = expectedSha256,
        architecture = SemanticModelArchitecture.BERT,
        embeddingDimension = SemanticModelProfileV01.EMBEDDING_DIMENSION,
        poolingType = SemanticPoolingType.MEAN,
        normalizationRule = SemanticNormalizationRule.L2,
        tokenizerProfileId = SemanticModelProfileV01.TOKENIZER_PROFILE_ID,
        runtimeIdentity = SemanticModelProfileV01.RUNTIME_IDENTITY
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private inline fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("semantic-artifact-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
