package pro.liliya.android.runtime

import kotlin.test.assertTrue
import org.junit.Test

class AndroidProductRuntimeStartupPortsCompositionContractTest {
    @Test
    fun assembly_preserves_exact_port_owners_without_replacement() {
        val admission = AndroidProductRuntimeStartupAdmissionPort {
            AndroidProductRuntimeAdmissionResult.Rejected(
                AndroidProductRuntimeAdmissionFailure.AUTHORITY_DENIED
            )
        }
        val dek = AndroidProductRuntimeStartupActiveDekPort {
            AndroidProductRuntimeStartupPreparationResult.Rejected
        }
        val semantic = AndroidProductRuntimeStartupSemanticPort {
            AndroidProductRuntimeStartupPreparationResult.Rejected
        }
        val model = AndroidProductRuntimeStartupModelPort {
            AndroidProductRuntimeStartupPreparationResult.Rejected
        }
        val prepared = AndroidProductRuntimeStartupPreparedInputsPort { _, _, _ ->
            AndroidProductRuntimeStartupPreparationResult.Rejected
        }

        val result = AndroidProductRuntimeStartupPortsComposition.assemble(
            AndroidProductRuntimeStartupPortSet(
                admission = admission,
                activeDek = dek,
                semantic = semantic,
                model = model,
                preparedInputs = prepared
            )
        )

        assertTrue(result.admission === admission)
        assertTrue(result.activeDek === dek)
        assertTrue(result.semantic === semantic)
        assertTrue(result.model === model)
        assertTrue(result.preparedInputs === prepared)
    }
}
