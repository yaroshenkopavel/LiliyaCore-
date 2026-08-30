package pro.liliya.core.devicekey

import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicLong
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

internal interface DeviceKeyRegistration {
    val state: DeviceKeyState
    val generation: DeviceKeyGeneration
    fun remove(context: LogContext): Boolean
}

internal sealed interface DeviceKeyRegistrationResult {
    data class Registered(val registration: DeviceKeyRegistration) : DeviceKeyRegistrationResult
    data class Rejected(val reason: String) : DeviceKeyRegistrationResult
}

internal class DeviceKeyStore(
    private val observability: CoreObservability
) {
    private data class Entry(
        val state: DeviceKeyState,
        val generation: DeviceKeyGeneration
    )

    private val lock = Any()
    private val nextGeneration = AtomicLong(0L)
    private val entries = mutableMapOf<DeviceKeyId, Entry>()

    fun register(
        state: DeviceKeyState,
        context: LogContext
    ): DeviceKeyRegistrationResult = synchronized(lock) {
        if (entries.containsKey(state.id)) {
            return@synchronized rejected(
                state = state,
                generation = null,
                reason = "device key id is already registered",
                context = context
            )
        }

        val nextValue = nextGeneration.incrementAndGet()
        if (nextValue <= 0L) {
            return@synchronized rejected(
                state = state,
                generation = null,
                reason = "device key generation overflow",
                context = context
            )
        }

        val detachedState = state.detached()
        val entry = Entry(detachedState, DeviceKeyGeneration(nextValue))
        entries[detachedState.id] = entry
        observeRegistered(entry, context)
        DeviceKeyRegistrationResult.Registered(registration(entry))
    }

    fun find(id: DeviceKeyId): DeviceKeyState? = synchronized(lock) {
        entries[id]?.state
    }

    fun inspect(id: DeviceKeyId): DeviceKeySnapshot? = synchronized(lock) {
        entries[id]?.let { DeviceKeySnapshot(it.state, it.generation) }
    }

    fun contains(id: DeviceKeyId): Boolean = synchronized(lock) {
        entries.containsKey(id)
    }

    fun snapshot(): List<DeviceKeyState> = snapshotEntries().map { it.state }

    fun snapshotEntries(): List<DeviceKeySnapshot> = synchronized(lock) {
        entries.values
            .map { DeviceKeySnapshot(it.state, it.generation) }
            .sortedWith(
                compareBy<DeviceKeySnapshot> { it.state.createdAt }
                    .thenBy { it.state.id.value }
            )
    }

    private fun registration(entry: Entry): DeviceKeyRegistration =
        object : DeviceKeyRegistration {
            override val state: DeviceKeyState = entry.state
            override val generation: DeviceKeyGeneration = entry.generation

            override fun remove(context: LogContext): Boolean = synchronized(lock) {
                val removed = entries[entry.state.id] === entry &&
                    entries.remove(entry.state.id) === entry
                observability.record(
                    severity = if (removed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                    code = if (removed) "DEVICE_KEY_REMOVED" else "DEVICE_KEY_REMOVAL_REJECTED",
                    message = if (removed) {
                        "device key state removed"
                    } else {
                        "device key registration is no longer current"
                    },
                    context = context,
                    metadata = metadata(entry.state, entry.generation)
                )
                removed
            }
        }

    private fun observeRegistered(entry: Entry, context: LogContext) {
        observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "DEVICE_KEY_REGISTERED",
            message = "device key state registered",
            context = context,
            metadata = metadata(entry.state, entry.generation)
        )
    }

    private fun rejected(
        state: DeviceKeyState,
        generation: DeviceKeyGeneration?,
        reason: String,
        context: LogContext
    ): DeviceKeyRegistrationResult.Rejected {
        observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "DEVICE_KEY_REGISTRATION_REJECTED",
            message = reason,
            context = context,
            metadata = metadata(state, generation) + ("rejectionReason" to reason)
        )
        return DeviceKeyRegistrationResult.Rejected(reason)
    }

    private fun metadata(
        state: DeviceKeyState,
        generation: DeviceKeyGeneration?
    ): Map<String, String> = buildMap {
        put("deviceKeyId", "[redacted]")
        generation?.let { put("deviceKeyGeneration", it.value.toString()) }
        put("deviceKeyAlgorithm", state.algorithm.value)
        put("deviceKeySecurityLevel", state.securityLevel.name.lowercase())
        put("deviceKeyCapabilityCount", state.capabilities.size.toString())
        put("createdAt", state.createdAt.toString())
    }

    private fun DeviceKeyState.detached(): DeviceKeyState = copy(
        capabilities = Collections.unmodifiableSet(LinkedHashSet(capabilities))
    )
}
