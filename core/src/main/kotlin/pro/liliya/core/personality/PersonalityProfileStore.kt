package pro.liliya.core.personality

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

internal interface PersonalityProfileRegistration {
    val profile: PersonalityProfile
    val generation: PersonalityGeneration
    fun remove(context: LogContext): Boolean
}

internal sealed interface PersonalityProfileRegistrationResult {
    data class Registered(val registration: PersonalityProfileRegistration) : PersonalityProfileRegistrationResult
    data class Rejected(val reason: String) : PersonalityProfileRegistrationResult
}

internal sealed interface PersonalityProfileRestorationResult {
    data class Restored(val store: PersonalityProfileStore) : PersonalityProfileRestorationResult
    data class Rejected(val reason: String) : PersonalityProfileRestorationResult
}

internal class PersonalityProfileStore private constructor(
    private val observability: CoreObservability,
    initialHighWatermark: Long,
    initialEntries: List<PersonalityProfileSnapshot>
) {
    private data class Entry(
        val generation: PersonalityGeneration,
        val profile: PersonalityProfile
    )

    constructor(observability: CoreObservability) : this(
        observability = observability,
        initialHighWatermark = 0L,
        initialEntries = emptyList()
    )

    private val nextGeneration = AtomicLong(initialHighWatermark)
    private val profiles = ConcurrentHashMap<PersonalityProfileId, Entry>().apply {
        initialEntries.forEach { snapshot ->
            put(snapshot.profile.id, Entry(snapshot.generation, snapshot.profile))
        }
    }

    fun register(
        profile: PersonalityProfile,
        context: LogContext
    ): PersonalityProfileRegistrationResult {
        val entry = Entry(
            generation = PersonalityGeneration(nextGeneration.incrementAndGet()),
            profile = profile
        )
        val previous = profiles.putIfAbsent(profile.id, entry)
        if (previous != null) {
            return rejectRegistration(profile, entry.generation, context, "personality profile id is already registered")
        }

        observeRegistered(profile, entry.generation, context)
        return PersonalityProfileRegistrationResult.Registered(registration(entry))
    }

    @Synchronized
    internal fun installCommitted(
        profile: PersonalityProfile,
        generation: PersonalityGeneration,
        highWatermark: Long,
        context: LogContext
    ): PersonalityProfileRegistrationResult {
        if (highWatermark < generation.value) {
            return PersonalityProfileRegistrationResult.Rejected("committed personality generation exceeds high watermark")
        }
        if (generation.value != highWatermark) {
            return PersonalityProfileRegistrationResult.Rejected("committed personality generation is not current high watermark")
        }
        if (highWatermark <= nextGeneration.get()) {
            return PersonalityProfileRegistrationResult.Rejected("committed personality generation is not newer than local high watermark")
        }
        if (profiles.containsKey(profile.id)) {
            return PersonalityProfileRegistrationResult.Rejected("personality profile id is already registered")
        }
        if (profiles.values.any { it.generation == generation }) {
            return PersonalityProfileRegistrationResult.Rejected("committed personality generation is already live")
        }

        val entry = Entry(generation = generation, profile = profile)
        if (profiles.putIfAbsent(profile.id, entry) != null) {
            return PersonalityProfileRegistrationResult.Rejected("personality profile id is already registered")
        }
        nextGeneration.set(highWatermark)
        observeRegistered(profile, generation, context)
        return PersonalityProfileRegistrationResult.Registered(registration(entry))
    }

    fun find(id: PersonalityProfileId): PersonalityProfile? = profiles[id]?.profile

    fun inspect(id: PersonalityProfileId): PersonalityProfileSnapshot? = profiles[id]?.let { entry ->
        PersonalityProfileSnapshot(profile = entry.profile, generation = entry.generation)
    }

    fun contains(id: PersonalityProfileId): Boolean = profiles.containsKey(id)

    fun snapshot(): List<PersonalityProfile> = snapshotEntries().map { it.profile }

    fun snapshotEntries(): List<PersonalityProfileSnapshot> = profiles.values
        .map { entry -> PersonalityProfileSnapshot(entry.profile, entry.generation) }
        .sortedWith(
            compareBy<PersonalityProfileSnapshot> { it.profile.createdAt }
                .thenBy { it.profile.id.value }
        )

    private fun registration(entry: Entry): PersonalityProfileRegistration = object : PersonalityProfileRegistration {
        override val profile: PersonalityProfile = entry.profile
        override val generation: PersonalityGeneration = entry.generation

        override fun remove(context: LogContext): Boolean {
            val removed = profiles.remove(entry.profile.id, entry)
            observability.record(
                severity = if (removed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                code = if (removed) "PERSONALITY_PROFILE_REMOVED" else "PERSONALITY_PROFILE_REMOVAL_REJECTED",
                message = if (removed) "personality profile removed" else "personality profile registration is no longer current",
                context = context,
                metadata = metadata(entry.profile, entry.generation)
            )
            return removed
        }
    }

    private fun rejectRegistration(
        profile: PersonalityProfile,
        generation: PersonalityGeneration,
        context: LogContext,
        reason: String
    ): PersonalityProfileRegistrationResult.Rejected {
        observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "PERSONALITY_PROFILE_REGISTRATION_REJECTED",
            message = reason,
            context = context,
            metadata = metadata(profile, generation) + ("rejectionReason" to reason)
        )
        return PersonalityProfileRegistrationResult.Rejected(reason)
    }

    private fun observeRegistered(
        profile: PersonalityProfile,
        generation: PersonalityGeneration,
        context: LogContext
    ) {
        observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "PERSONALITY_PROFILE_REGISTERED",
            message = "personality profile registered",
            context = context,
            metadata = metadata(profile, generation)
        )
    }

    private fun metadata(
        profile: PersonalityProfile,
        generation: PersonalityGeneration
    ): Map<String, String> = buildMap {
        put("personalityProfileId", profile.id.value)
        put("personalityGeneration", generation.value.toString())
        put("createdAt", profile.createdAt.toString())
        put("personalityAttributeCount", profile.attributes.size.toString())
        put("personalitySourceId", profile.provenance.sourceId.value)
        profile.provenance.sourceReference?.let { reference -> put("personalitySourceReference", reference.value) }
        when (val target = profile.target) {
            is PersonalityTarget.Self -> {
                put("personalityTargetType", "self")
                put("selfIdentityId", target.identityId.value)
                put("selfGeneration", target.generation.value.toString())
            }
        }
    }

    companion object {
        fun restore(
            observability: CoreObservability,
            entries: List<PersonalityProfileSnapshot>,
            highWatermark: Long
        ): PersonalityProfileRestorationResult {
            if (highWatermark < 0L) {
                return PersonalityProfileRestorationResult.Rejected("personality generation high watermark is negative")
            }
            if (entries.any { it.generation.value > highWatermark }) {
                return PersonalityProfileRestorationResult.Rejected("personality generation exceeds restored high watermark")
            }
            if (entries.map { it.profile.id }.toSet().size != entries.size) {
                return PersonalityProfileRestorationResult.Rejected("duplicate restored personality profile id")
            }
            if (entries.map { it.generation }.toSet().size != entries.size) {
                return PersonalityProfileRestorationResult.Rejected("duplicate restored personality generation")
            }
            return PersonalityProfileRestorationResult.Restored(
                PersonalityProfileStore(
                    observability = observability,
                    initialHighWatermark = highWatermark,
                    initialEntries = entries.map { PersonalityProfileSnapshot(it.profile, it.generation) }
                )
            )
        }
    }
}
