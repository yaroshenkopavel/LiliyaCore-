package pro.liliya.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.android.semanticprovider.AndroidOfflineSemanticArtifactProvisionResult
import pro.liliya.android.semanticprovider.AndroidOfflineSemanticArtifactProvisioner
import pro.liliya.android.semanticprovider.AndroidOfflineSemanticProvisionedLocationResult

@RunWith(AndroidJUnit4::class)
class DevelopmentFirstWorkingLiliyaColdStartFixturePrerequisiteInstrumentedTest {

    @Test
    fun exact_semantic_bundle_and_pinned_real_gguf_are_available_to_android_app_fixture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext.applicationContext
        val testContext = instrumentation.context
        val semanticRoot = File(
            targetContext.filesDir,
            AndroidOfflineSemanticArtifactProvisioner.DEFAULT_DIRECTORY
        )
        semanticRoot.deleteRecursively()

        try {
            val provisioner = AndroidOfflineSemanticArtifactProvisioner()
            val provisioned = testContext.assets
                .open(SEMANTIC_ENCODER_ASSET)
                .use { encoder ->
                    testContext.assets.open(SEMANTIC_TOKENIZER_ASSET).use { tokenizer ->
                        provisioner.provision(
                            context = targetContext,
                            encoderInput = encoder,
                            tokenizerInput = tokenizer
                        )
                    }
                }

            assertIs<AndroidOfflineSemanticArtifactProvisionResult.Provisioned>(provisioned)

            val resolved = assertIs<AndroidOfflineSemanticProvisionedLocationResult.Ready>(
                provisioner.resolveProvisioned(targetContext)
            )
            assertTrue(resolved.root.isDirectory)
            assertTrue(resolved.encoderFile.isFile)

            testContext.assets.open(STORIES_15M_ASSET).use { model ->
                var total = 0L
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = model.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read.toLong()
                }
                buffer.fill(0)
                assertEquals(STORIES_15M_BYTES, total)
            }
        } finally {
            semanticRoot.deleteRecursively()
        }
    }

    private companion object {
        const val SEMANTIC_ENCODER_ASSET = "multilingual-e5-small-liliya-v0.1.onnx"
        const val SEMANTIC_TOKENIZER_ASSET = "multilingual-e5-small-tokenizer-v0.1.onnx"
        const val STORIES_15M_ASSET = "stories15M-q4_0.gguf"
        const val STORIES_15M_BYTES = 19_077_344L
    }
}
