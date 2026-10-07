package pro.liliya.core.asf

/**
 * Serializes append calls before delegating to a potentially non-thread-safe audit ledger.
 *
 * The wrapper preserves each producer thread's program order and prevents concurrent delegate
 * invocation. Cross-agent append order is intentionally not treated as authority; audit events
 * already carry explicit instance/generation/root identity and timestamps.
 */
class SynchronizedAgentAuditLedger(
    private val delegate: AgentAuditLedger
) : AgentAuditLedger {
    private val lock = Any()

    override fun append(event: AgentAuditEvent) {
        synchronized(lock) {
            delegate.append(event)
        }
    }
}
