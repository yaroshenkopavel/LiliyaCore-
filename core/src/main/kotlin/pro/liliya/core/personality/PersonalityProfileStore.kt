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

internal class PersonalityProfileStore(
    private val observability: CoreObservability
) {
    private data class Entry(
        val generation: PersonalityGeneration,
        val profile: PersonalityProfile
    )

    private val nextGeneration = AtomicLong(0)
    private val profiles = ConcurrentHashMap<PersonalityProfileId, Entry>()

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
            val reason = "personality profile id is already registered"
            observability.record(
                severity = DiagnosticSeverity.WARNING,
                code = "PERSONALITY_PROFILE_REGISTRATION_REJECTED",
                message = reason,
                context = context,
                metadata = metadata(profile, entry.generation) + ("rejectionReason" to reason)
            )
            return PersonalityProfileRegistrationResult.Rejected(reason)
        }

        observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "PERSONALITY_PROFILE_REGISTERED",
            message = "personality profile registered",
            context = context,
            metadata = metadata(profile, entry.generation)
        )

        return PersonalityProfileRegistrationResult.Registered(
            registration = object : PersonalityProfileRegistration {
                override val profile: PersonalityProfile = profile
                override val generation: PersonalityGeneration = entry.generation

                override fun remove(context: LogContext): Boolean {
                    val removed = profiles.remove(profile.id, entry)
                    observability.record(
                        severity = if (removed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                        code = if (removed) {
                            "PERSONALITY_PROFILE_REMOVED"
                        } else {
                            "PERSONALITY_PROFILE_REMOVAL_REJECTED"
                        },
                        message = if (removed) {
                            "personality profile removed"
                        } else {
                            "personality profile registration is no longer current"
                        },
                        context = context,
                        metadata = metadata(profile, entry.generation)
                    )
                    return removed
                }
            }
        )
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

    private fun metadata(
        profile: PersonalityProfile,
        generation: PersonalityGeneration
    ): Map<String, String> = buildMap {
        put("personalityProfileId", profile.id.value)
        put("personalityGeneration", generation.value.toString())
        put("createdAt", profile.createdAt.toString())
        put("personalityAttributeCount", profile.attributes.size.toString())
        put("personalitySourceId", profile.provenance.sourceId.value)
        profile.provenance.sourceReference?.let { reference ->
            put("personalitySourceReference", reference.value)
        }
        when (val target = profile.target) {
            is PersonalityTarget.Self -> {
                put("personalityTargetType", "self")
                put("selfIdentityId", target.identityId.value)
                put("selfGeneration", target.generation.value.toString())
            }
        }
    }
}
