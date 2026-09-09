package pro.liliya.app

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test
import pro.liliya.android.runtime.AndroidProductRuntimeAdmissionFailure
import pro.liliya.android.runtime.AndroidProductRuntimeAdmissionResult

class ProductionAndroidRuntimeWiringContractTest {
    @After
    fun cleanup() {
        ProductionAndroidAppTrustedWiring.clearForTests()
    }

    @Test
    fun rejected_admission_fails_closed_before_runtime_preparation() {
        var preparedCalls = 0
        var bootstrapCalls = 0
        val port = ProductionAndroidRuntimeWiring.createStartPort(
            sources = ProductionAndroidRuntimeWiringSources(
                admission = {
                    AndroidProductRuntimeAdmissionResult.Rejected(
                        AndroidProductRuntimeAdmissionFailure.AUTHORITY_DENIED
                    )
                },
                preparedInputs = {
                    preparedCalls += 1
                    error("prepared inputs must not be requested")
                }
            ),
            bootstrap = ProductionAndroidRuntimeBootstrapPort { _, _ ->
                bootstrapCalls += 1
                error("bootstrap must not be called")
            }
        )

        val result = port.start()

        val rejected = assertIs<ProductionAndroidAppRuntimeStartResult.Rejected>(result)
        assertEquals(
            ProductionAndroidAppRuntimeFailure.BOOTSTRAP_REJECTED,
            rejected.reason
        )
        assertEquals(0, preparedCalls)
        assertEquals(0, bootstrapCalls)
    }

    @Test
    fun admission_source_exception_is_bounded_and_preparation_is_not_called() {
        var preparedCalls = 0
        val port = ProductionAndroidRuntimeWiring.createStartPort(
            ProductionAndroidRuntimeWiringSources(
                admission = { error("private admission failure") },
                preparedInputs = {
                    preparedCalls += 1
                    error("must not execute")
                }
            )
        )

        val result = port.start()

        val rejected = assertIs<ProductionAndroidAppRuntimeStartResult.Rejected>(result)
        assertEquals(
            ProductionAndroidAppRuntimeFailure.BOOTSTRAP_REJECTED,
            rejected.reason
        )
        assertEquals(0, preparedCalls)
    }

    @Test
    fun install_is_single_owner_and_does_not_replace_existing_trusted_wiring() {
        val first = ProductionAndroidRuntimeWiringSources(
            admission = {
                AndroidProductRuntimeAdmissionResult.Rejected(
                    AndroidProductRuntimeAdmissionFailure.AUTHORITY_DENIED
                )
            },
            preparedInputs = { error("must not execute") }
        )
        val second = ProductionAndroidRuntimeWiringSources(
            admission = {
                AndroidProductRuntimeAdmissionResult.Rejected(
                    AndroidProductRuntimeAdmissionFailure.INTERNAL_FAILURE
                )
            },
            preparedInputs = { error("must not execute") }
        )

        assertTrue(ProductionAndroidRuntimeWiring.install(first))
        val installed = ProductionAndroidAppTrustedWiring.current()
        assertFalse(ProductionAndroidRuntimeWiring.install(second))
        assertTrue(ProductionAndroidAppTrustedWiring.current() === installed)
    }
}
