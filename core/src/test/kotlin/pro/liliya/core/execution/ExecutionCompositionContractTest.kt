package pro.liliya.core.execution

import java.lang.reflect.Modifier
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.AuthorityScope
import pro.liliya.core.authority.CapabilityId
import pro.liliya.core.authority.ScopedAuthorityGrant
import pro.liliya.core.authority.ScopedGrantAuthorityPolicy
import pro.liliya.core.authority.AuthorityManager
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExecutionCompositionContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val diagnostics: InMemoryDiagnosticSink,
        val foundation: FoundationComposition
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val diagnostics = InMemoryDiagnosticSink()
        val recorder = DiagnosticRecorder(diagnostics)
        val foundation = FoundationComposition(
            diagnostics = recorder,
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) }
        )
        return Fixture(logs, diagnostics, foundation)
    }

    private val principal = AuthorityPrincipal("executor")
    private val capability = CapabilityId("device.launch")
    private val scope = AuthorityScope("app:maps")
    private val actionId = ExecutionActionId("launch.maps")
    private val request = ExecutionRequest(
        principal = principal,
        capability = capability,
        scope = scope,
        actionId = actionId,
        reason = "launch maps"
    )

    @Test
    fun denied_authority_never_reaches_owned_executor() {
        val f = fixture()
        var calls = 0
        val composition = ExecutionComposition(
            foundation = f.foundation,
            authorityPolicy = ScopedGrantAuthorityPolicy(),
            executor = ExecutionExecutor { _, _ ->
                calls += 1
                ExecutionResult.Succeeded
            },
            actionCapabilities = mapOf(actionId to capability)
        )

        val result = composition.execute(request)

        assertTrue(result is ExecutionResult.Rejected)
        assertEquals(0, calls)
        assertEquals(
            listOf("AUTHORITY_DENIED", "EXECUTION_REJECTED"),
            f.logs.snapshot().map { it.marker }
        )
    }

    @Test
    fun granted_execution_uses_composition_owned_pipeline_and_correlation() {
        val f = fixture()
        var calls = 0
        val composition = ExecutionComposition(
            foundation = f.foundation,
            authorityPolicy = ScopedGrantAuthorityPolicy(
                listOf(ScopedAuthorityGrant(principal, capability, scope))
            ),
            executor = ExecutionExecutor { _, _ ->
                calls += 1
                ExecutionResult.Succeeded
            },
            actionCapabilities = mapOf(actionId to capability)
        )

        assertEquals(ExecutionResult.Succeeded, composition.execute(request))
        assertEquals(1, calls)
        assertEquals(
            listOf("AUTHORITY_GRANTED", "EXECUTION_SUCCEEDED"),
            f.logs.snapshot().map { it.marker }
        )
        assertEquals(
            listOf("AUTHORITY_GRANTED", "EXECUTION_SUCCEEDED"),
            f.diagnostics.snapshot().map { it.code }
        )

        val correlations =
            f.logs.snapshot().map { it.context.correlationId } +
                f.diagnostics.snapshot().map { it.context.correlationId }
        assertEquals(1, correlations.toSet().size)
        assertTrue(correlations.singleOrNull()?.isNotBlank() ?: correlations.first().isNotBlank())
    }

    @Test
    fun runtime_api_does_not_expose_executor_or_managers() {
        val forbidden = setOf(
            ExecutionExecutor::class.java,
            ExecutionManager::class.java,
            AuthorityManager::class.java
        )

        val exposed = ExecutionComposition::class.java.methods.filter { method ->
            Modifier.isPublic(method.modifiers) && method.returnType in forbidden
        }

        assertTrue(exposed.isEmpty(), "runtime API must not expose executor or managers: $exposed")
    }
}
