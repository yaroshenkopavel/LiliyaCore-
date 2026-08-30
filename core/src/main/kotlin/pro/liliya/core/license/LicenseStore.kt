package pro.liliya.core.license

import java.util.concurrent.atomic.AtomicLong
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

internal interface LicenseRegistration {
    val entitlement: LicenseEntitlement
    val generation: LicenseGeneration
    fun remove(context: LogContext): Boolean
}

internal sealed interface LicenseRegistrationResult {
    data class Registered(val registration: LicenseRegistration) : LicenseRegistrationResult
    data class Rejected(val reason: String) : LicenseRegistrationResult
}

internal class LicenseStore(
    private val observability: CoreObservability
) {
    private data class Entry(
        val entitlement: LicenseEntitlement,
        val generation: LicenseGeneration
    )

    private val lock = Any()
    private val nextGeneration = AtomicLong(0L)
    private val entries = mutableMapOf<LicenseId, Entry>()

    fun register(
        entitlement: LicenseEntitlement,
        context: LogContext
    ): LicenseRegistrationResult = synchronized(lock) {
        if (entries.containsKey(entitlement.id)) {
            return@synchronized rejected(
                entitlement,
                null,
                "license id is already registered",
                context
            )
        }

        val nextValue = nextGeneration.incrementAndGet()
        if (nextValue <= 0L) {
            return@synchronized rejected(
                entitlement,
                null,
                "license generation overflow",
                context
            )
        }
        val entry = Entry(
            entitlement = entitlement,
            generation = LicenseGeneration(nextValue)
        )
        entries[entitlement.id] = entry
        observeRegistered(entry, context)
        LicenseRegistrationResult.Registered(registration(entry))
    }

    fun find(id: LicenseId): LicenseEntitlement? = synchronized(lock) {
        entries[id]?.entitlement
    }

    fun inspect(id: LicenseId): LicenseSnapshot? = synchronized(lock) {
        entries[id]?.let { LicenseSnapshot(it.entitlement, it.generation) }
    }

    fun contains(id: LicenseId): Boolean = synchronized(lock) {
        entries.containsKey(id)
    }

    fun snapshot(): List<LicenseEntitlement> = snapshotEntries().map { it.entitlement }

    fun snapshotEntries(): List<LicenseSnapshot> = synchronized(lock) {
        entries.values
            .map { LicenseSnapshot(it.entitlement, it.generation) }
            .sortedWith(
                compareBy<LicenseSnapshot> { it.entitlement.issuedAt }
                    .thenBy { it.entitlement.id.value }
            )
    }

    private fun registration(entry: Entry): LicenseRegistration =
        object : LicenseRegistration {
            override val entitlement: LicenseEntitlement = entry.entitlement
            override val generation: LicenseGeneration = entry.generation

            override fun remove(context: LogContext): Boolean = synchronized(lock) {
                val removed = entries[entry.entitlement.id] === entry &&
                    entries.remove(entry.entitlement.id) === entry
                observability.record(
                    severity = if (removed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                    code = if (removed) "LICENSE_REMOVED" else "LICENSE_REMOVAL_REJECTED",
                    message = if (removed) {
                        "license state removed"
                    } else {
                        "license registration is no longer current"
                    },
                    context = context,
                    metadata = metadata(entry.entitlement, entry.generation)
                )
                removed
            }
        }

    private fun observeRegistered(entry: Entry, context: LogContext) {
        observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "LICENSE_REGISTERED",
            message = "license state registered",
            context = context,
            metadata = metadata(entry.entitlement, entry.generation)
        )
    }

    private fun rejected(
        entitlement: LicenseEntitlement,
        generation: LicenseGeneration?,
        reason: String,
        context: LogContext
    ): LicenseRegistrationResult.Rejected {
        observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "LICENSE_REGISTRATION_REJECTED",
            message = reason,
            context = context,
            metadata = metadata(entitlement, generation) + ("rejectionReason" to reason)
        )
        return LicenseRegistrationResult.Rejected(reason)
    }

    private fun metadata(
        entitlement: LicenseEntitlement,
        generation: LicenseGeneration?
    ): Map<String, String> = buildMap {
        put("licenseId", entitlement.id.value)
        generation?.let { put("licenseGeneration", it.value.toString()) }
        put("licenseProductId", entitlement.productId.value)
        put("licenseVersion", entitlement.version.value.toString())
        put("licenseSigningKeyId", entitlement.signingKeyId.value)
        put("licenseFeatureCount", entitlement.features.size.toString())
        put("licenseRevocationEpoch", entitlement.revocationEpoch.value.toString())
        entitlement.replaySequence?.let { put("licenseReplaySequence", it.value.toString()) }
    }
}
