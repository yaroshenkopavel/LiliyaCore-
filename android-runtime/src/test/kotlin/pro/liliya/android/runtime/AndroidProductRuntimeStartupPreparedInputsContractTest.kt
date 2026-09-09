package pro.liliya.android.runtime

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import pro.liliya.core.encryption.CognitiveDekGeneration
import pro.liliya.core.encryption.CognitiveDekId
import pro.liliya.core.encryption.CognitiveDekReference

class AndroidProductRuntimeStartupPreparedInputsContractTest {
    @Test
    fun adapter_forwards_exact_dynamic_provisioning_ownership() {
        val dek = CognitiveDekReference(
            id = CognitiveDekId("prepared-inputs-dek"),
            generation = CognitiveDekGeneration(3)
        )
        val semantic = AndroidProductRuntimeSemanticArtifacts(
            root = File("semantic-root"),
            encoderFile = File("semantic-root/encoder.onnx")
        )
        val staged = object : pro.liliya.core.protectedmodel.LargeProtectedModelStagedSourceOwnership {
            override val source: pro.liliya.core.protectedmodel.LargeProtectedModelStagedSource
                get() = error("unused staged source")

            override fun retire(): pro.liliya.core.protectedmodel.LargeProtectedModelStagingRetireResult =
                pro.liliya.core.protectedmodel.LargeProtectedModelStagingRetireResult.Retired
        }
        var called = false

        val adapter = AndroidProductRuntimeStartupPreparedInputsAdapter(
            AndroidProductRuntimeStartupPreparedInputsBuildPort { actualDek, actualSemantic, actualStaged ->
                called = true
                assertEquals(dek, actualDek)
                assertTrue(actualSemantic === semantic)
                assertTrue(actualStaged === staged)
                AndroidProductRuntimeStartupPreparationResult.Rejected
            }
        )

        val result = adapter.prepare(dek, semantic, staged)

        assertTrue(called)
        assertIs<AndroidProductRuntimeStartupPreparationResult.Rejected>(result)
    }

    @Test
    fun adapter_bounds_builder_exception() {
        val adapter = AndroidProductRuntimeStartupPreparedInputsAdapter(
            AndroidProductRuntimeStartupPreparedInputsBuildPort { _, _, _ ->
                error("private prepared-input builder failure")
            }
        )

        val result = runCatching {
            adapter.prepare(
                CognitiveDekReference(
                    id = CognitiveDekId("prepared-inputs-dek"),
                    generation = CognitiveDekGeneration(1)
                ),
                AndroidProductRuntimeSemanticArtifacts(
                    root = File("semantic-root"),
                    encoderFile = File("semantic-root/encoder.onnx")
                ),
                object : pro.liliya.core.protectedmodel.LargeProtectedModelStagedSourceOwnership {
                    override val source: pro.liliya.core.protectedmodel.LargeProtectedModelStagedSource
                        get() = error("unused staged source")

                    override fun retire(): pro.liliya.core.protectedmodel.LargeProtectedModelStagingRetireResult =
                        pro.liliya.core.protectedmodel.LargeProtectedModelStagingRetireResult.Retired
                }
            )
        }

        assertIs<AndroidProductRuntimeStartupPreparationResult.Rejected>(result.getOrThrow())
    }
}
