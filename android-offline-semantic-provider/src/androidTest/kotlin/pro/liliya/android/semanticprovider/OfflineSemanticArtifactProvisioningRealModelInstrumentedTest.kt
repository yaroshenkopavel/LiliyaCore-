package pro.liliya.android.semanticprovider

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineSemanticArtifactProvisioningRealModelInstrumentedTest {

    @Test
    fun exact_reproduced_bundle_provisions_app_private_and_loads_through_public_opaque_boundary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext.applicationContext
        val testContext = instrumentation.context
        val root = File(targetContext.filesDir, "offline-semantic-provider-v0.1")
        root.deleteRecursively()

        try {
            val provisioner = AndroidOfflineSemanticArtifactProvisioner.create(targetContext)
            val result = testContext.assets.open(SemanticModelProfileV01.ONNX_FILE_NAME).use { encoder ->
                testContext.assets.open(SemanticModelProfileV01.TOKENIZER_ONNX_FILE_NAME).use { tokenizer ->
                    provisioner.provision(encoder, tokenizer)
                }
            }
            val provisioned = assertIs<AndroidOfflineSemanticProvisioningResult.Provisioned>(result)

            val provider = AndroidOfflineSemanticProviderAssembly.create()
            assertEquals(
                AndroidOfflineSemanticProviderLoadResult.Loaded,
                provider.load(provisioned.bundle)
            )
            assertEquals(
                AndroidOfflineSemanticProviderCloseResult.Closed,
                provider.close()
            )

            val second = testContext.assets.open(SemanticModelProfileV01.ONNX_FILE_NAME).use { encoder ->
                testContext.assets.open(SemanticModelProfileV01.TOKENIZER_ONNX_FILE_NAME).use { tokenizer ->
                    provisioner.provision(encoder, tokenizer)
                }
            }
            assertIs<AndroidOfflineSemanticProvisioningResult.AlreadyProvisioned>(second)
        } finally {
            root.deleteRecursively()
        }
    }
}
