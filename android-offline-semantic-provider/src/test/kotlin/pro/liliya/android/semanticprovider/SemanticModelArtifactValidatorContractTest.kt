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
    fun exact_app_private_production_artifact_validates_without_exposing_path_or_digest() {
        withRoot { root ->
            val bytes = "semantic-model-fixture".toByteArray()
            val file = File(root, "models/semantic.gguf").also {
                it.parentFile!!.mkdirs()
                it.writeBytes(bytes)
            }
            val spec = productionSpec(bytes, file.name)

            val result = SemanticModelArtifactValidator(root).validate(file, spec)
            val validated = assertIs<SemanticModelArtifactValidationResult.Validated>(result)

            assertEquals(file.canonicalFile, validated.artifact.file)
            assertEquals(spec, validated.artifact.spec)
            assertTrue(!validated.toString().contains(file.absolutePath))
            assertTrue(!validated.toString().contains(spec.expectedSha256))
            assertTrue(!spec.toString().contains(spec.expectedSha256))
        }
    }

    @Test
    fun benchmark_only_provenance_requires_explicit_non_production_acceptance() {
        withRoot { root ->
            val identity = ControlledBenchmarkSemanticModelArtifactV01.identity
            val file = File(root, identity.ggufFileName).also { fixture ->
                fixture.writeBytes(ByteArray(identity.expectedSizeBytes.toInt()))
            }
            val spec = SemanticModelArtifactSpec(identity)

            assertIs<SemanticModelArtifactValidationResult.IncompleteConversionProvenance>(
                SemanticModelArtifactValidator(root).validate(file, spec)
            )

            // Size/hash are deliberately not the real benchmark bytes, so explicit benchmark mode
            // progresses past provenance and then fails structurally at the cryptographic boundary.
            assertIs<SemanticModelArtifactValidationResult.DigestMismatch>(
                SemanticModelArtifactValidator(
                    root,
                    SemanticModelArtifactAcceptance.CONTROLLED_BENCHMARK
                ).validate(file, spec)
            )
        }
    }

    @Test
    fun profile_and_file_name_mismatch_fail_before_file_validation() {
        withRoot { root ->
            val bytes = "semantic-model-fixture".toByteArray()
            val file = File(root, "semantic.gguf").also { it.writeBytes(bytes) }
            val mismatchedProfile = productionSpec(
                bytes = bytes,
                fileName = file.name,
                profileId = "wrong-profile"
            )
            assertIs<SemanticModelArtifactValidationResult.ProfileMismatch>(
                SemanticModelArtifactValidator(root).validate(file, mismatchedProfile)
            )

            val wrongName = productionSpec(bytes, "different.gguf")
            assertIs<SemanticModelArtifactValidationResult.FileNameMismatch>(
                SemanticModelArtifactValidator(root).validate(file, wrongName)
            )
        }
    }

    @Test
    fun missing_directory_size_and_digest_fail_structurally() {
        withRoot { root ->
            val validator = SemanticModelArtifactValidator(root)
            val missing = File(root, "missing.gguf")
            assertIs<SemanticModelArtifactValidationResult.Missing>(
                validator.validate(missing, productionSpec("x".toByteArray(), missing.name))
            )

            val directory = File(root, "directory").also { it.mkdirs() }
            assertIs<SemanticModelArtifactValidationResult.NotRegularFile>(
                validator.validate(directory, productionSpec("x".toByteArray(), directory.name))
            )

            val file = File(root, "model.gguf").also { it.writeText("actual") }
            val expected = "expected".toByteArray()
            assertIs<SemanticModelArtifactValidationResult.SizeMismatch>(
                validator.validate(file, productionSpec(expected, file.name))
            )

            val sameSizeWrongDigest = "xxxxxx".toByteArray()
            assertEquals(file.length(), sameSizeWrongDigest.size.toLong())
            assertIs<SemanticModelArtifactValidationResult.DigestMismatch>(
                validator.validate(file, productionSpec(sameSizeWrongDigest, file.name))
            )
        }
    }

    @Test
    fun outside_root_and_symlink_escape_are_rejected_before_hashing() {
        val root = Files.createTempDirectory("semantic-root").toFile()
        val outsideRoot = Files.createTempDirectory("semantic-outside").toFile()
        try {
            val outside = File(outsideRoot, "semantic.gguf").also { it.writeText("secret") }
            val validator = SemanticModelArtifactValidator(root)

            assertIs<SemanticModelArtifactValidationResult.OutsideAppPrivateRoot>(
                validator.validate(
                    outside,
                    productionSpec("secret".toByteArray(), outside.name)
                )
            )

            val link = File(root, "linked.gguf")
            try {
                Files.createSymbolicLink(link.toPath(), outside.toPath())
                assertIs<SemanticModelArtifactValidationResult.OutsideAppPrivateRoot>(
                    validator.validate(
                        link,
                        productionSpec("secret".toByteArray(), link.name)
                    )
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
                fileName = "semantic.gguf",
                expectedSizeBytes = 0L
            )
            error("expected size validation")
        } catch (_: IllegalArgumentException) {
        }
        try {
            productionIdentity(
                bytes = "x".toByteArray(),
                fileName = "semantic.gguf",
                expectedSha256 = "ABC"
            )
            error("expected digest validation")
        } catch (_: IllegalArgumentException) {
        }
        try {
            productionIdentity("x".toByteArray(), "../semantic.gguf")
            error("expected filename validation")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun productionSpec(
        bytes: ByteArray,
        fileName: String,
        profileId: String = SemanticModelProfileV01.PROFILE_ID
    ): SemanticModelArtifactSpec =
        SemanticModelArtifactSpec(productionIdentity(bytes, fileName, profileId = profileId))

    private fun productionIdentity(
        bytes: ByteArray,
        fileName: String,
        profileId: String = SemanticModelProfileV01.PROFILE_ID,
        expectedSizeBytes: Long = bytes.size.toLong(),
        expectedSha256: String = sha256(bytes)
    ): SemanticModelArtifactIdentity = SemanticModelArtifactIdentity(
        profileId = profileId,
        profileGeneration = SemanticModelProfileV01.PROFILE_GENERATION,
        upstreamModelRepository = SemanticModelProfileV01.UPSTREAM_MODEL_REPOSITORY,
        upstreamModelRevision = SemanticModelProfileV01.UPSTREAM_MODEL_REVISION,
        conversionProvenance = SemanticConversionProvenance.Reproducible(
            artifactRepository = "test/reproducible-semantic-artifact",
            artifactRevision = "test-artifact-revision",
            conversionToolRevision = "test-conversion-tool-revision"
        ),
        ggufFileName = fileName,
        expectedSizeBytes = expectedSizeBytes,
        expectedSha256 = expectedSha256,
        architecture = SemanticModelArchitecture.BERT,
        embeddingDimension = SemanticModelProfileV01.EMBEDDING_DIMENSION,
        poolingType = SemanticPoolingType.MEAN,
        normalizationRule = SemanticNormalizationRule.L2,
        tokenizerProfileId = SemanticModelProfileV01.TOKENIZER_PROFILE_ID,
        llamaCppRevision = SemanticModelProfileV01.LLAMA_CPP_REVISION
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
