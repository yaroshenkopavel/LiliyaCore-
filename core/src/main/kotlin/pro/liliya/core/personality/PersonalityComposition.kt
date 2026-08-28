package pro.liliya.core.personality

import pro.liliya.core.foundation.FoundationComposition

interface PersonalityOwnership {
    val profile: PersonalityProfile
    val generation: PersonalityGeneration
    fun remove(): Boolean
}

sealed interface PersonalityInstallResult {
    data class Installed(val ownership: PersonalityOwnership) : PersonalityInstallResult
    data class Rejected(val reason: String) : PersonalityInstallResult
}

class PersonalityComposition(
    private val foundation: FoundationComposition
) {
    private val store = PersonalityProfileStore(foundation.observability)

    fun install(profile: PersonalityProfile): PersonalityInstallResult {
        val context = foundation.rootContext(
            operation = "installPersonalityProfile",
            component = "Personality",
            metadata = profileMetadata(profile)
        )
        return when (val result = store.register(profile, context)) {
            is PersonalityProfileRegistrationResult.Registered -> PersonalityInstallResult.Installed(
                ownership = object : PersonalityOwnership {
                    override val profile: PersonalityProfile = result.registration.profile
                    override val generation: PersonalityGeneration = result.registration.generation

                    override fun remove(): Boolean = result.registration.remove(
                        foundation.rootContext(
                            operation = "removePersonalityProfile",
                            component = "Personality",
                            metadata = profileMetadata(profile) + mapOf(
                                "personalityGeneration" to generation.value.toString()
                            )
                        )
                    )
                }
            )

            is PersonalityProfileRegistrationResult.Rejected ->
                PersonalityInstallResult.Rejected(result.reason)
        }
    }

    fun find(id: PersonalityProfileId): PersonalityProfile? = store.find(id)

    fun inspect(id: PersonalityProfileId): PersonalityProfileSnapshot? = store.inspect(id)

    fun contains(id: PersonalityProfileId): Boolean = store.contains(id)

    fun snapshot(): List<PersonalityProfile> = store.snapshot()

    fun snapshotEntries(): List<PersonalityProfileSnapshot> = store.snapshotEntries()

    private fun profileMetadata(profile: PersonalityProfile): Map<String, String> = buildMap {
        put("personalityProfileId", profile.id.value)
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
