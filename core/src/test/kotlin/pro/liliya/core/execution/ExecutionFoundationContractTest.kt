package pro.liliya.core.execution

import pro.liliya.core.authority.AuthorityManager
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.AuthorityScope
import pro.liliya.core.authority.CapabilityId
import pro.liliya.core.authority.ScopedAuthorityGrant
import pro.liliya.core.authority.ScopedGrantAuthorityPolicy
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.LogContextPropagation
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.CoreObservability
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ExecutionFoundationContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val diagnostics: InMemoryDiagnosticSink,
        val observability: CoreObservability
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val diagnostics = InMemoryDiagnosticSink()
        return Fixture(
            logs = logs,
            diagnostics = diagnostics,
            observability = CoreObservability(
                loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
                diagnostics = DiagnosticRecorder(diagnostics)
            )
        )
    }

    private val principal = AuthorityPrincipal("executor")
    private val capability = CapabilityId("device.launch")
    private val scope = AuthorityScope("app:maps")
    private val actionId = ExecutionActionId("launch.maps")

    @Test
    fun action_id_and_reason_require_explicit_identity() {
        assertFailsWith<IllegalArgumentException> { ExecutionActionId(" ") }
        assertFailsWith<IllegalArgumentException> {
            ExecutionRequest(principal, capability, scope, actionId, " ")
        }
    }

    @Test
    fun denied_authority_never_invokes_executor() {
        val f = fixture()
        var calls = 0
        val manager = ExecutionManager(
            authorityManager = AuthorityManager(
                policy = ScopedGrantAuthorityPolicy(),
                observability = f.observability
            ),
            executor = ExecutionExecutor { _, _ ->
                calls += 1
                ExecutionResult.Succeeded
            },
            observability = f.observability
        )

        val result = manager.execute(
            ExecutionRequest(principal, capability, scope, actionId, "launch maps"),
            context("execution-denied")
        )

        assertIs<ExecutionResult.Rejected>(result)
        assertEquals(0, calls)
        assertEquals(
            listOf("AUTHORITY_DENIED", "EXECUTION_REJECTED"),
            f.logs.snapshot().map { it.marker }
        )
    }

    @Test
    fun granted_authority_executes_once_and_preserves_correlation() {
        val f = fixture()
        var calls = 0
        val manager = ExecutionManager(
            authorityManager = AuthorityManager(
                policy = ScopedGrantAuthorityPolicy(
                    listOf(ScopedAuthorityGrant(principal, capability, scope))
                ),
                observability = f.observability
            ),
            executor = ExecutionExecutor { _, _ ->
                calls += 1
                ExecutionResult.Succeeded
            },
            observability = f.observability
        )

        assertEquals(
            ExecutionResult.Succeeded,
            manager.execute(
                ExecutionRequest(principal, capability, scope, actionId, "launch maps"),
                context("execution-granted")
            )
        )
        assertEquals(1, calls)
        assertEquals(
            listOf("AUTHORITY_GRANTED", "EXECUTION_SUCCEEDED"),
            f.logs.snapshot().map { it.marker }
        )
        assertEquals(
            listOf("AUTHORITY_GRANTED", "EXECUTION_SUCCEEDED"),
            f.diagnostics.snapshot().map { it.code }
        )
        assertEquals(
            setOf("execution-granted"),
            (f.logs.snapshot().map { it.context.correlationId } +
                f.diagnostics.snapshot().map { it.context.correlationId }).toSet()
        )
    }

    @Test
    fun executor_exception_isolated_as_failed_result() {
        val f = fixture()
        val failure = IllegalStateException("adapter failed")
        val manager = ExecutionManager(
            authorityManager = AuthorityManager(
                policy = ScopedGrantAuthorityPolicy(
                    listOf(ScopedAuthorityGrant(principal, capability, scope))
                ),
                observability = f.observability
            ),
            executor = ExecutionExecutor { _, _ -> throw failure },
            observability = f.observability
        )

        val result = assertIs<ExecutionResult.Failed>(
            manager.execute(
                ExecutionRequest(principal, capability, scope, actionId, "launch maps"),
                context("execution-failed")
            )
        )

        assertEquals("adapter failed", result.reason)
        assertEquals(failure, result.throwable)
        assertEquals(
            listOf("AUTHORITY_GRANTED", "EXECUTION_FAILED"),
            f.logs.snapshot().map { it.marker }
        )
        assertEquals(failure.javaClass.name, f.logs.snapshot().last().throwableType)
        assertEquals(failure.message, f.logs.snapshot().last().throwableMessage)
        assertEquals(failure.javaClass.name, f.diagnostics.snapshot().last().throwableType)
        assertEquals(failure.message, f.diagnostics.snapshot().last().throwableMessage)
    }

    private fun context(correlationId: String) = LogContextPropagation.root(
        module = "CORE",
        component = "Execution",
        operation = "execute",
        generator = CorrelationIdGenerator { correlationId }
    )
}
