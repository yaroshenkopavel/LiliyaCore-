package pro.liliya.android.semanticprovider

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AndroidOfflineSemanticArtifactProvisionerContractTest {

    @Test
    fun exact_pair_is_provisioned_and_second_call_is_already_provisioned() {
        withProvisioner { provisioner, root, spec, encoderBytes, tokenizerBytes ->
            val first = assertIs<AndroidOfflineSemanticProvisioningResult.Provisioned>(
                provisioner.provision(
                    ByteArrayInputStream(encoderBytes),
                    ByteArrayInputStream(tokenizerBytes)
                )
            )
            assertContentEquals(
                encoderBytes,
                java.io.File(root, spec.encoderFileName).readBytes()
            )
            assertContentEquals(
                tokenizerBytes,
                java.io.File(root, spec.tokenizerFileName).readBytes()
            )
            assertFalse(first.bundle.toString().contains(root.absolutePath))

            val second = assertIs<AndroidOfflineSemanticProvisioningResult.AlreadyProvisioned>(
                provisioner.provision(
                    ByteArrayInputStream(encoderBytes),
                    ByteArrayInputStream(tokenizerBytes)
                )
            )
            assertFalse(second.bundle.toString().contains(root.absolutePath))
        }
    }

    @Test
    fun truncated_or_oversized_source_is_rejected_without_publication() {
        withProvisioner { provisioner, root, spec, encoderBytes, tokenizerBytes ->
            assertEquals(
                AndroidOfflineSemanticProvisioningResult.SourceRejected,
                provisioner.provision(
                    ByteArrayInputStream(encoderBytes.copyOf(encoderBytes.size - 1)),
                    ByteArrayInputStream(tokenizerBytes)
                )
            )
            assertFalse(java.io.File(root, spec.encoderFileName).exists())
            assertFalse(java.io.File(root, spec.tokenizerFileName).exists())

            assertEquals(
                AndroidOfflineSemanticProvisioningResult.SourceRejected,
                provisioner.provision(
                    ByteArrayInputStream(encoderBytes + byteArrayOf(9)),
                    ByteArrayInputStream(tokenizerBytes)
                )
            )
            assertFalse(java.io.File(root, spec.encoderFileName).exists())
            assertFalse(java.io.File(root, spec.tokenizerFileName).exists())
        }
    }

    @Test
    fun wrong_digest_is_rejected_without_publication() {
        withProvisioner { provisioner, root, spec, encoderBytes, tokenizerBytes ->
            val corrupted = encoderBytes.copyOf().also {
                it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            }
            assertEquals(
                AndroidOfflineSemanticProvisioningResult.SourceRejected,
                provisioner.provision(
                    ByteArrayInputStream(corrupted),
                    ByteArrayInputStream(tokenizerBytes)
                )
            )
            assertFalse(java.io.File(root, spec.encoderFileName).exists())
            assertFalse(java.io.File(root, spec.tokenizerFileName).exists())
        }
    }

    @Test
    fun partial_or_corrupt_existing_bundle_fails_closed_without_overwrite() {
        withProvisioner { provisioner, root, spec, encoderBytes, tokenizerBytes ->
            val encoderTarget = java.io.File(root, spec.encoderFileName)
            root.mkdirs()
            encoderTarget.writeBytes(byteArrayOf(1, 2, 3))

            assertEquals(
                AndroidOfflineSemanticProvisioningResult.ExistingBundleRejected,
                provisioner.provision(
                    ByteArrayInputStream(encoderBytes),
                    ByteArrayInputStream(tokenizerBytes)
                )
            )
            assertContentEquals(byteArrayOf(1, 2, 3), encoderTarget.readBytes())
            assertFalse(java.io.File(root, spec.tokenizerFileName).exists())
        }
    }

    @Test
    fun stale_temporary_siblings_are_never_authoritative() {
        withProvisioner { provisioner, root, spec, encoderBytes, tokenizerBytes ->
            root.mkdirs()
            java.io.File(root, ".encoder.importing").writeBytes(byteArrayOf(7, 7))
            java.io.File(root, ".tokenizer.importing").writeBytes(byteArrayOf(8, 8))

            assertIs<AndroidOfflineSemanticProvisioningResult.Provisioned>(
                provisioner.provision(
                    ByteArrayInputStream(encoderBytes),
                    ByteArrayInputStream(tokenizerBytes)
                )
            )
            assertFalse(java.io.File(root, ".encoder.importing").exists())
            assertFalse(java.io.File(root, ".tokenizer.importing").exists())
            assertContentEquals(
                encoderBytes,
                java.io.File(root, spec.encoderFileName).readBytes()
            )
        }
    }

    private inline fun withProvisioner(
        block: (
            AndroidOfflineSemanticArtifactProvisioner,
            java.io.File,
            SemanticArtifactProvisioningSpec,
            ByteArray,
            ByteArray
        ) -> Unit
    ) {
        val root = Files.createTempDirectory("semantic-provisioner-test").toFile()
        val encoder = "tiny-encoder-payload".encodeToByteArray()
        val tokenizer = "tiny-tokenizer-payload".encodeToByteArray()
        val spec = SemanticArtifactProvisioningSpec(
            encoderFileName = "encoder.onnx",
            encoderBytes = encoder.size.toLong(),
            encoderSha256 = sha256(encoder),
            tokenizerFileName = "tokenizer.onnx",
            tokenizerBytes = tokenizer.size.toLong(),
            tokenizerSha256 = sha256(tokenizer)
        )
        val provisioner = AndroidOfflineSemanticArtifactProvisioner(
            root = root,
            spec = spec,
            requireProductionValidator = false
        )
        try {
            block(provisioner, root, spec, encoder, tokenizer)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
}
