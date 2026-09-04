package pro.liliya.android.semanticprovider

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.extensions.OrtxPackage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnnxSemanticStructuralProbeInstrumentedTest {

    @Test
    fun synthetic_text_crosses_tokenizer_and_encoder_boundary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val targetContext = instrumentation.targetContext
        val root = File(targetContext.filesDir, "onnx-semantic-structural-probe")
        root.deleteRecursively()
        check(root.mkdirs())

        val encoder = File(root, SemanticModelProfileV01.ONNX_FILE_NAME)
        val tokenizer = File(root, SemanticModelProfileV01.TOKENIZER_ONNX_FILE_NAME)
        try {
            copyAsset(testContext, SemanticModelProfileV01.ONNX_FILE_NAME, encoder)
            copyAsset(testContext, SemanticModelProfileV01.TOKENIZER_ONNX_FILE_NAME, tokenizer)

            val environment = OrtEnvironment.getEnvironment()
            environment.setTelemetry(false)

            val tokenIds = try {
                OrtSession.SessionOptions().use { options ->
                    options.registerCustomOpLibrary(OrtxPackage.getLibraryPath())
                    environment.createSession(tokenizer.absolutePath, options).use { session ->
                        assertEquals(setOf("inputs"), session.inputNames, "TOKENIZER_INPUT_CONTRACT")
                        assertTrue(session.outputNames.contains("tokens"), "TOKENIZER_OUTPUT_CONTRACT")
                        OnnxTensor.createTensor(environment, arrayOf("query: hello")).use { input ->
                            session.run(mapOf("inputs" to input), setOf("tokens")).use { result ->
                                val output = result.get("tokens").orElse(null)
                                    ?: fail("TOKENIZER_OUTPUT_MISSING")
                                extractLongVector(output.value)
                                    ?: fail("TOKENIZER_VALUE_SHAPE")
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
                fail("TOKENIZER_EXECUTION")
            }

            assertTrue(tokenIds.isNotEmpty(), "TOKENIZER_EMPTY")
            assertTrue(tokenIds.size <= 512, "TOKENIZER_UNEXPECTED_BOUND")

            try {
                OrtSession.SessionOptions().use { options ->
                    environment.createSession(encoder.absolutePath, options).use { session ->
                        assertTrue(
                            session.inputNames.containsAll(
                                setOf("input_ids", "attention_mask", "token_type_ids")
                            ),
                            "ENCODER_INPUT_CONTRACT"
                        )
                        assertEquals(setOf("embedding"), session.outputNames, "ENCODER_OUTPUT_CONTRACT")

                        val shape = longArrayOf(1L, tokenIds.size.toLong())
                        val ids = tensor(environment, tokenIds, shape)
                        val maskValues = LongArray(tokenIds.size) { 1L }
                        val mask = tensor(environment, maskValues, shape)
                        val typeValues = LongArray(tokenIds.size)
                        val types = tensor(environment, typeValues, shape)
                        try {
                            session.run(
                                mapOf(
                                    "input_ids" to ids,
                                    "attention_mask" to mask,
                                    "token_type_ids" to types
                                ),
                                setOf("embedding")
                            ).use { result ->
                                val output = result.get("embedding").orElse(null)
                                    ?: fail("ENCODER_OUTPUT_MISSING")
                                val vector = extractFloatVector(output.value)
                                    ?: fail("ENCODER_VALUE_SHAPE")
                                assertEquals(
                                    SemanticEmbeddingVector.DIMENSION,
                                    vector.size,
                                    "ENCODER_DIMENSION"
                                )
                                assertTrue(vector.all(Float::isFinite), "ENCODER_NONFINITE")
                                vector.fill(0f)
                            }
                        } finally {
                            ids.close()
                            mask.close()
                            types.close()
                            tokenIds.fill(0L)
                            maskValues.fill(0L)
                            typeValues.fill(0L)
                        }
                    }
                }
            } catch (_: Throwable) {
                fail("ENCODER_EXECUTION")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun copyAsset(context: android.content.Context, name: String, target: File) {
        context.assets.open(name).use { input ->
            target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        }
    }

    private fun tensor(
        environment: OrtEnvironment,
        values: LongArray,
        shape: LongArray
    ): OnnxTensor {
        val buffer = ByteBuffer.allocateDirect(values.size * Long.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asLongBuffer()
        buffer.put(values)
        buffer.flip()
        return OnnxTensor.createTensor(environment, buffer, shape)
    }

    private fun extractLongVector(value: Any?): LongArray? = when (value) {
        is LongArray -> value.copyOf()
        is Array<*> -> {
            if (value.size != 1) null else (value[0] as? LongArray)?.copyOf()
        }
        else -> null
    }

    private fun extractFloatVector(value: Any?): FloatArray? = when (value) {
        is FloatArray -> value.copyOf()
        is Array<*> -> {
            if (value.size != 1) null else (value[0] as? FloatArray)?.copyOf()
        }
        else -> null
    }
}
