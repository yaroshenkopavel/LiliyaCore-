package pro.liliya.core.agent

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import pro.liliya.core.autonomy.AutonomyDeliberationRequestId

class ControlledAgentCoordinationDeliberationPrivacyContractTest {
    @Test
    fun private_objective_is_redacted_from_spec_rendering() {
        val secret = "never-render-coordination-deliberation-objective"
        val spec = AgentCoordinationDeliberationSpec(
            participant = ExactAgentReference(AgentId("agent-private"), AgentGeneration(7)),
            requestId = AutonomyDeliberationRequestId("private-deliberation-request"),
            objective = secret,
            createdAt = Instant.parse("2026-08-30T06:20:00Z")
        )

        assertFalse(spec.toString().contains(secret))
    }
}
