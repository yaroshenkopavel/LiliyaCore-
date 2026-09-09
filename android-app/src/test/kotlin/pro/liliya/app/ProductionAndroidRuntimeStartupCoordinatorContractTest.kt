package pro.liliya.app

import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import pro.liliya.android.runtime.AndroidProductRuntimeStartupRequestSourceFailure

class ProductionAndroidRuntimeStartupCoordinatorContractTest {
    @Test
    fun missing_input_stays_configuration_required_without_provisioning() {
        val coordinator = ProductionAndroidRuntimeStartupCoordinator()
        var provisionCalls = 0
        var startCalls = 0

        val result = coordinator.start(
            hasStartupInput = false,
            configuredPort = ProductionAndroidRuntimeConfiguredPort { false },
            provisionPort = ProductionAndroidRuntimeStartupProvisionPort {
                provisionCalls += 1
                error("must not provision")
            },
            runtimeStartPort = ProductionAndroidRuntimeStartStatePort {
                startCalls += 1
                ProductionAndroidAppRuntimeState.READY
            }
        )

        assertIs<ProductionAndroidAppStartupOutcome.ConfigurationRequired>(result)
        assertEquals(0, provisionCalls)
        assertEquals(0, startCalls)
    }

    @Test
    fun successful_provisioning_starts_runtime_once() {
        val coordinator = ProductionAndroidRuntimeStartupCoordinator()
        var provisionCalls = 0
        var startCalls = 0

        val result = coordinator.start(
            hasStartupInput = true,
            configuredPort = ProductionAndroidRuntimeConfiguredPort { false },
            provisionPort = ProductionAndroidRuntimeStartupProvisionPort {
                provisionCalls += 1
                ProductionAndroidRuntimeStartupSourceInstallResult.Installed
            },
            runtimeStartPort = ProductionAndroidRuntimeStartStatePort {
                startCalls += 1
                ProductionAndroidAppRuntimeState.READY
            }
        )

        val runtime = assertIs<ProductionAndroidAppStartupOutcome.Runtime>(result)
        assertEquals(ProductionAndroidAppRuntimeState.READY, runtime.state)
        assertEquals(1, provisionCalls)
        assertEquals(1, startCalls)
    }

    @Test
    fun source_rejection_is_terminal_and_not_retried() {
        val coordinator = ProductionAndroidRuntimeStartupCoordinator()
        var provisionCalls = 0
        val provision = ProductionAndroidRuntimeStartupProvisionPort {
            provisionCalls += 1
            ProductionAndroidRuntimeStartupSourceInstallResult.SourceRejected(
                AndroidProductRuntimeStartupRequestSourceFailure.COGNITIVE_STORAGE_CORRUPT
            )
        }

        val first = coordinator.start(
            hasStartupInput = true,
            configuredPort = ProductionAndroidRuntimeConfiguredPort { false },
            provisionPort = provision,
            runtimeStartPort = ProductionAndroidRuntimeStartStatePort {
                error("must not start")
            }
        )
        val second = coordinator.start(
            hasStartupInput = true,
            configuredPort = ProductionAndroidRuntimeConfiguredPort { false },
            provisionPort = provision,
            runtimeStartPort = ProductionAndroidRuntimeStartStatePort {
                error("must not start")
            }
        )

        assertIs<ProductionAndroidAppStartupOutcome.SourceRejected>(first)
        assertIs<ProductionAndroidAppStartupOutcome.SourceRejected>(second)
        assertEquals(1, provisionCalls)
    }

    @Test
    fun existing_runtime_configuration_skips_provisioning() {
        val coordinator = ProductionAndroidRuntimeStartupCoordinator()
        var provisionCalls = 0
        var startCalls = 0

        val result = coordinator.start(
            hasStartupInput = true,
            configuredPort = ProductionAndroidRuntimeConfiguredPort { true },
            provisionPort = ProductionAndroidRuntimeStartupProvisionPort {
                provisionCalls += 1
                error("must not provision")
            },
            runtimeStartPort = ProductionAndroidRuntimeStartStatePort {
                startCalls += 1
                ProductionAndroidAppRuntimeState.READY
            }
        )

        val runtime = assertIs<ProductionAndroidAppStartupOutcome.Runtime>(result)
        assertEquals(ProductionAndroidAppRuntimeState.READY, runtime.state)
        assertEquals(0, provisionCalls)
        assertEquals(1, startCalls)
    }
}
