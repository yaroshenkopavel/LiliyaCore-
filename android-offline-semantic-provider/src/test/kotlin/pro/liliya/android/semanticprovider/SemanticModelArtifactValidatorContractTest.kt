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
    fun exact_app_private_artifact_validates_without_exposing_path_or_digest() {
        withRoot { root ->
            val bytes = "semantic-model-fixture".toByteArray()
            val file = File(root, "models/semantic.gguf").also {
                it.parentFile!!.mkdirs()
                it.writeBytes(bytes)
            }
            val spec = spec(bytes)

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
    fun missing_directory_size_and_digest_fail_structurally() {
        withRoot { root ->
            val validator = SemanticModelArtifactValidator(root)
            val missing = File(root, "missing.gguf")
            assertIs<SemanticModelArtifactValidationResult.Missing>(
                validator.validate(missing, spec("x".toByteArray()))
            )

            val directory = File(root, "directory").also { it.mkdirs() }
            assertIs<SemanticModelArtifactValidationResult.NotRegularFile>(
                validator.validate(directory, spec("x".toByteArray()))
            )

            val file = File(root, "model.gguf").also { it.writeText("actual") }
            val expected = "expected".toByteArray()
            assertIs<SemanticModelArtifactValidationResult.SizeMismatch>(
                validator.validate(file, spec(expected))
            )

            val sameSizeWrongDigest = "xxxxxx".toByteArray()
            assertEquals(file.length(), sameSizeWrongDigest.size.toLong())
            assertIs<SemanticModelArtifactValidationResult.DigestMismatch>(
                validator.validate(file, spec(sameSizeWrongDigest))
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
            val artifactSpec = spec("secret".toByteArray())

            assertIs<SemanticModelArtifactValidationResult.OutsideAppPrivateRoot>(
                validator.validate(outside, artifactSpec)
            )

            val link = File(root, "linked.gguf")
            try {
                Files.createSymbolicLink(link.toPath(), outside.toPath())
                assertIs<SemanticModelArtifactValidationResult.OutsideAppPrivateRoot>(
                    validator.validate(link, artifactSpec)
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
    fun malformed_spec_is_rejected_before_validation() {
        val generation = SemanticProfileGeneration(1)
        try {
            SemanticModelArtifactSpec(generation, 0L, "0".repeat(64))
            error("expected size validation")
        } catch (_: IllegalArgumentException) {
        }
        try {
            SemanticModelArtifactSpec(generation, 1L, "ABC")
            error("expected digest validation")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun spec(bytes: ByteArray): SemanticModelArtifactSpec =
        SemanticModelArtifactSpec(
            profileGeneration = SemanticProfileGeneration(1),
            expectedSizeBytes = bytes.size.toLong(),
            expectedSha256 = MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        )

    private inline fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("semantic-artifact-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
