package pro.liliya.android.runtime

import pro.liliya.core.asf.AgentCoordinator
import pro.liliya.core.asf.AgentCoordinatorPlan
import pro.liliya.core.asf.AgentCoordinatorResult
import pro.liliya.core.asf.AgentCoordinatorRunWindow
import pro.liliya.core.asf.AgentParallelCoordinator

fun interface AndroidProductRuntimeAdvisoryAgentRunPort {
    fun run(
        plan: AgentCoordinatorPlan,
        window: AgentCoordinatorRunWindow,
        cancelled: () -> Boolean
    ): AgentCoordinatorResult
}

fun interface AndroidProductRuntimeAdvisoryAgentParallelRunPort {
    fun run(
        plan: AgentCoordinatorPlan,
        window: AgentCoordinatorRunWindow,
        timeoutPerWaveMillis: Long,
        cancelled: () -> Boolean
    ): AgentCoordinatorResult
}

/**
 * Advisory-only product bridge over the bounded ASF coordinator.
 *
 * This host deliberately owns no AuthorityPrincipal, capability grant, Execution port,
 * orchestration action gateway or durable permission-bearing state. Agent artifacts are advisory
 * cognitive outputs only. Any later controlled action must cross the existing governed
 * ControlledAutonomyExecution / GovernedClosedLoopActionGateway boundary with fresh Authority.
 */
class AndroidProductRuntimeAdvisoryAgentHost internal constructor(
    private val runPort: AndroidProductRuntimeAdvisoryAgentRunPort,
    private val parallelRunPort: AndroidProductRuntimeAdvisoryAgentParallelRunPort? = null
) {
    constructor(coordinator: AgentCoordinator) : this(
        AndroidProductRuntimeAdvisoryAgentRunPort { plan, window, cancelled ->
            coordinator.runSequential(
                plan = plan,
                runWindow = window,
                cancelled = cancelled
            )
        }
    )

    internal constructor(
        coordinator: AgentCoordinator,
        parallelCoordinator: AgentParallelCoordinator
    ) : this(
        runPort = AndroidProductRuntimeAdvisoryAgentRunPort { plan, window, cancelled ->
            coordinator.runSequential(
                plan = plan,
                runWindow = window,
                cancelled = cancelled
            )
        },
        parallelRunPort = AndroidProductRuntimeAdvisoryAgentParallelRunPort {
                plan,
                window,
                timeoutPerWaveMillis,
                cancelled ->
            parallelCoordinator.run(
                plan = plan,
                runWindow = window,
                timeoutPerWaveMillis = timeoutPerWaveMillis,
                cancelled = cancelled
            )
        }
    )

    fun run(
        plan: AgentCoordinatorPlan,
        window: AgentCoordinatorRunWindow,
        cancelled: () -> Boolean = { false }
    ): AgentCoordinatorResult =
        runPort.run(
            plan = plan,
            window = window,
            cancelled = cancelled
        )

    fun runParallel(
        plan: AgentCoordinatorPlan,
        window: AgentCoordinatorRunWindow,
        timeoutPerWaveMillis: Long,
        cancelled: () -> Boolean = { false }
    ): AgentCoordinatorResult? =
        parallelRunPort?.run(
            plan = plan,
            window = window,
            timeoutPerWaveMillis = timeoutPerWaveMillis,
            cancelled = cancelled
        )
}
