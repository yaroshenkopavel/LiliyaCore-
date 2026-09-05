package pro.liliya.android.semanticprovider

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidOfflineSemanticArtifactProvisionerInstrumentedTest {

    @Test
    fun exact_bundle_provisions_revalidates_loads_and_reopens_as_already_provisioned() =
        withCleanRoot { context, root ->
            val provisioner = AndroidOfflineSemanticArtifactProvisioner()

            val first = context.assets.open(SemanticModelProfileV01.ONNX_FILE_NAME).use { encoder ->
                context.assets.open(SemanticModelProfileV01.TOKENIZER_ONNX_FILE_NAME).use { tokenizer ->
                    provisioner.provision(
                        context = context,
                        encoderInput = encoder,
                        tokenizerInput = tokenizer,
                        directoryName = TEST_DIRECTORY
                    )
                }
            }
            assertEquals(
                AndroidOfflineSemanticArtifactProvisionResult.Provisioned,
                first
            )

            val encoderFile = File(root, SemanticModelProfileV01.ONNX_FILE_NAME)
            val tokenizerFile = File(root, SemanticModelProfileV01.TOKENIZER_ONNX_FILE_NAME)
            assertTrue(encoderFile.isFile)
            assertTrue(tokenizerFile.isFile)
            assertEquals(SemanticModelProfileV01.ONNX_SIZE_BYTES, encoderFile.length())
            assertEquals(
                SemanticModelProfileV01.TOKENIZER_ONNX_SIZE_BYTES,
                tokenizerFile.length()
            )

            val identity = productionSemanticModelIdentity()
            assertIs<SemanticModelArtifactValidationResult.Validated>(
                SemanticModelArtifactValidator(root, identity).validate(
                    encoderFile,
                    SemanticModelArtifactSpec(identity)
                )
            )

            val provider = AndroidOfflineSemanticProviderAssembly.create()
            assertEquals(
                AndroidOfflineSemanticProviderLoadResult.Loaded,
                provider.loadProvisioned(root)
            )
            assertEquals(
                AndroidOfflineSemanticProviderCloseResult.Closed,
                provider.close()
            )

            val second = context.assets.open(SemanticModelProfileV01.ONNX_FILE_NAME).use { encoder ->
                context.assets.open(SemanticModelProfileV01.TOKENIZER_ONNX_FILE_NAME).use { tokenizer ->
                    provisioner.provision(
                        context = context,
                        encoderInput = encoder,
                        tokenizerInput = tokenizer,
                        directoryName = TEST_DIRECTORY
                    )
                }
            }
            assertEquals(
                AndroidOfflineSemanticArtifactProvisionResult.AlreadyProvisioned,
                second
            )
        }

    @Test
    fun truncated_wrong_digest_and_oversized_tokenizer_never_publish_bundle() =
        withCleanRoot { context, root ->
            val provisioner = AndroidOfflineSemanticArtifactProvisioner()
            val emptyEncoder = ByteArrayInputStream(byteArrayOf())

            val truncated = provisioner.provision(
                context = context,
                encoderInput = emptyEncoder,
                tokenizerInput = ByteArrayInputStream(byteArrayOf(1, 2, 3)),
                directoryName = TEST_DIRECTORY
            )
            assertEquals(
                AndroidOfflineSemanticArtifactProvisionResult.SourceRejected,
                truncated
            )
            assertFalse(File(root, SemanticModelProfileV01.ONNX_FILE_NAME).exists())
            assertFalse(File(root, SemanticModelProfileV01.TOKENIZER_ONNX_FILE_NAME).exists())

            val wrongDigest = provisioner.provision(
                context = context,
                encoderInput = ByteArrayInputStream(byteArrayOf()),
                tokenizerInput = RepeatingInputStream(
                    SemanticModelProfileV01.TOKENIZER_ONNX_SIZE_BYTES,
                    0x5A
                ),
                directoryName = TEST_DIRECTORY
            )
            assertEquals(
                AndroidOfflineSemanticArtifactProvisionResult.SourceRejected,
                wrongDigest
            )
            assertFalse(File(root, SemanticModelProfileV01.ONNX_FILE_NAME).exists())
            assertFalse(File(root, SemanticModelProfileV01.TOKENIZER_ONNX_FILE_NAME).exists())

            val oversized = provisioner.provision(
                context = context,
                encoderInput = ByteArrayInputStream(byteArrayOf()),
                tokenizerInput = RepeatingInputStream(
                    SemanticModelProfileV01.TOKENIZER_ONNX_SIZE_BYTES + 1L,
                    0x33
                ),
                directoryName = TEST_DIRECTORY
            )
            assertEquals(
                AndroidOfflineSemanticArtifactProvisionResult.SourceRejected,
                oversized
            )
            assertFalse(File(root, SemanticModelProfileV01.ONNX_FILE_NAME).exists())
            assertFalse(File(root, SemanticModelProfileV01.TOKENIZER_ONNX_FILE_NAME).exists())
        }

    @Test
    fun corrupt_published_member_requires_explicit_replace() =
        withCleanRoot { context, root ->
            val provisioner = AndroidOfflineSemanticArtifactProvisioner()
            val tokenizer = File(root, SemanticModelProfileV01.TOKENIZER_ONNX_FILE_NAME)
            tokenizer.parentFile?.mkdirs()
            tokenizer.writeBytes(byteArrayOf(9, 8, 7))

            val installOnly = provisioner.provision(
                context = context,
                encoderInput = ByteArrayInputStream(byteArrayOf()),
                tokenizerInput = ByteArrayInputStream(byteArrayOf()),
                directoryName = TEST_DIRECTORY
            )
            assertEquals(
                AndroidOfflineSemanticArtifactProvisionResult.ExistingPublishedStateRejected,
                installOnly
            )
            assertTrue(tokenizer.isFile)
            assertEquals(3L, tokenizer.length())

            val replaced = context.assets.open(SemanticModelProfileV01.ONNX_FILE_NAME).use { encoder ->
                context.assets.open(SemanticModelProfileV01.TOKENIZER_ONNX_FILE_NAME).use { exactTokenizer ->
                    provisioner.provision(
                        context = context,
                        encoderInput = encoder,
                        tokenizerInput = exactTokenizer,
                        directoryName = TEST_DIRECTORY,
                        mode = AndroidOfflineSemanticArtifactProvisionMode.EXPLICIT_REPLACE
                    )
                }
            }
            assertEquals(
                AndroidOfflineSemanticArtifactProvisionResult.Provisioned,
                replaced
            )
            assertEquals(
                SemanticModelProfileV01.TOKENIZER_ONNX_SIZE_BYTES,
                tokenizer.length()
            )
        }

    private inline fun withCleanRoot(
        block: (android.content.Context, File) -> Unit
    ) {
        val context = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext
        val root = File(
            context.filesDir,
            TEST_DIRECTORY
        )
        root.deleteRecursively()
        try {
            block(context, root)
        } finally {
            root.deleteRecursively()
        }
    }

    private class RepeatingInputStream(
        private val totalBytes: Long,
        private val value: Int
    ) : InputStream() {
        private var emitted = 0L

        override fun read(): Int {
            if (emitted >= totalBytes) return -1
            emitted += 1L
            return value and 0xff
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int
        ): Int {
            if (emitted >= totalBytes) return -1
            val count = minOf(length.toLong(), totalBytes - emitted).toInt()
            java.util.Arrays.fill(
                buffer,
                offset,
                offset + count,
                (value and 0xff).toByte()
            )
            emitted += count.toLong()
            return count
        }
    }

    private companion object {
        const val TEST_DIRECTORY = "semantic-artifact-provisioning-test"
    }
}
