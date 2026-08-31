package pro.liliya.core.runtime.hardening

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuntimeModelOperationSupervisorOwnershipContractTest {
    @Test
    fun one_registry_cannot_own_multiple_operation_supervisors() {
        val registry = RuntimeModelSessionRegistry()
        val limits = RuntimeHardeningLimits(maxInFlightOperationsPerSession = 1)

        RuntimeModelOperationSupervisor(
            registry = registry,
            limits = limits
        )

        val failure = assertFailsWith<IllegalStateException> {
            RuntimeModelOperationSupervisor(
                registry = registry,
                limits = limits
            )
        }

        assertTrue(failure.message.orEmpty().contains("already owns an operation supervisor"))
    }
}
