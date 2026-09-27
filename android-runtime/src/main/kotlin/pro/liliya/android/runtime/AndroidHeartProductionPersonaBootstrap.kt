package pro.liliya.android.runtime

import java.time.Instant
import pro.liliya.core.cognitive.PersonalitySnapshotPort
import pro.liliya.core.cognitive.SelfSnapshotPort
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.identity.SelfComposition
import pro.liliya.core.identity.SelfIdentity
import pro.liliya.core.identity.SelfIdentityId
import pro.liliya.core.identity.SelfIdentitySnapshot
import pro.liliya.core.identity.SelfInstallResult
import pro.liliya.core.identity.SelfName
import pro.liliya.core.identity.SelfOrigin
import pro.liliya.core.identity.SelfSourceId
import pro.liliya.core.identity.SelfSourceReference
import pro.liliya.core.personality.EncryptedPersistentPersonalityComposition
import pro.liliya.core.personality.PersistentPersonalityInstallResult
import pro.liliya.core.personality.PersistentPersonalityMutationResult
import pro.liliya.core.personality.PersonalityAttribute
import pro.liliya.core.personality.PersonalityComposition
import pro.liliya.core.personality.PersonalityGeneration
import pro.liliya.core.personality.PersonalityInstallResult
import pro.liliya.core.personality.PersonalityProfile
import pro.liliya.core.personality.PersonalityProfileId
import pro.liliya.core.personality.PersonalityProfileSnapshot
import pro.liliya.core.personality.PersonalityProvenance
import pro.liliya.core.personality.PersonalitySourceId
import pro.liliya.core.personality.PersonalitySourceReference
import pro.liliya.core.personality.PersonalityTarget

data class AndroidHeartProductionPersonaLimits(
    val maxSelfIdentityIdChars: Int = 256,
    val maxSelfNameChars: Int = 256,
    val maxSourceIdChars: Int = 256,
    val maxSourceReferenceChars: Int = 1_024,
    val maxPersonalityProfileIdChars: Int = 256,
    val maxPersonalityAttributes: Int = 64,
    val maxPersonalityAttributeKeyChars: Int = 256,
    val maxPersonalityAttributeValueChars: Int = 4_096
) {
    init {
        require(maxSelfIdentityIdChars > 0)
        require(maxSelfNameChars > 0)
        require(maxSourceIdChars > 0)
        require(maxSourceReferenceChars > 0)
        require(maxPersonalityProfileIdChars > 0)
        require(maxPersonalityAttributes > 0)
        require(maxPersonalityAttributeKeyChars > 0)
        require(maxPersonalityAttributeValueChars > 0)
    }
}

/**
 * Stable declarative product persona input.
 *
 * Runtime Self/Personality generations are intentionally absent. They are fresh lifecycle
 * identities created by the bootstrap for each product/Heart composition.
 */
class AndroidHeartProductionPersonaDefinition(
    val selfIdentityId: SelfIdentityId,
    val selfName: SelfName,
    val selfSourceId: SelfSourceId,
    val selfSourceReference: SelfSourceReference?,
    val selfCreatedAt: Instant,
    val personalityProfileId: PersonalityProfileId,
    personalityAttributes: List<PersonalityAttribute>,
    val personalitySourceId: PersonalitySourceId,
    val personalitySourceReference: PersonalitySourceReference?,
    val personalityCreatedAt: Instant
) {
    val personalityAttributes: List<PersonalityAttribute> = personalityAttributes.toList()

    init {
        require(this.personalityAttributes.isNotEmpty()) {
            "production persona must define at least one personality attribute"
        }
    }

    override fun toString(): String =
        "AndroidHeartProductionPersonaDefinition(" +
            "selfIdentityId=<redacted>,selfName=<redacted>," +
            "selfSourceId=<redacted>,selfSourceReference=<redacted>," +
            "selfCreatedAt=" + selfCreatedAt + "," +
            "personalityProfileId=<redacted>," +
            "personalityAttributeCount=" + personalityAttributes.size + "," +
            "personalitySourceId=<redacted>,personalitySourceReference=<redacted>," +
            "personalityCreatedAt=" + personalityCreatedAt + ")"
}

enum class AndroidHeartProductionPersonaCreateFailure {
    DEFINITION_REJECTED,
    SELF_INSTALL_REJECTED,
    SELF_SNAPSHOT_MISMATCH,
    PERSONALITY_INSTALL_REJECTED,
    PERSONALITY_SNAPSHOT_MISMATCH
}

sealed interface AndroidHeartProductionPersonaCreateResult {
    data class Ready(
        val composition: AndroidHeartProductionPersonaComposition
    ) : AndroidHeartProductionPersonaCreateResult

    data class Rejected(
        val reason: AndroidHeartProductionPersonaCreateFailure
    ) : AndroidHeartProductionPersonaCreateResult
}

/**
 * Exact runtime ownership of one product persona.
 *
 * Self remains a fresh lifecycle identity. Personality may be freshly installed or reopened from
 * authenticated durable storage, but remains descriptive state only and never Authority.
 * Cognitive Runtime receives only read-only snapshot ports over the exact owned Self/Personality.
 */
internal interface AndroidHeartProductionPersonaSelfPort {
    fun install(identity: SelfIdentity): SelfInstallResult
    fun inspect(): SelfIdentitySnapshot?
}

internal interface AndroidHeartProductionPersonaPersonalityPort {
    fun install(profile: PersonalityProfile): PersonalityInstallResult
    fun inspect(id: PersonalityProfileId): PersonalityProfileSnapshot?
    fun snapshotEntries(): List<PersonalityProfileSnapshot>
}

class AndroidHeartProductionPersonaComposition internal constructor(
    private val self: AndroidHeartProductionPersonaSelfPort,
    private val personality: AndroidHeartProductionPersonaPersonalityPort,
    val installedSelf: SelfIdentitySnapshot,
    val installedPersonality: PersonalityProfileSnapshot
) {
    val selfSnapshots: SelfSnapshotPort = SelfSnapshotPort {
        self.inspect()?.takeIf { it == installedSelf }
    }

    val personalitySnapshots: PersonalitySnapshotPort = PersonalitySnapshotPort {
        personality.snapshotEntries().filter { it == installedPersonality }
    }

    override fun toString(): String =
        "AndroidHeartProductionPersonaComposition(" +
            "self=<redacted>,personality=<redacted>," +
            "installedSelf=<redacted>,installedPersonality=<redacted>)"
}

object AndroidHeartProductionPersonaBootstrap {

    fun create(
        foundation: FoundationComposition,
        definition: AndroidHeartProductionPersonaDefinition,
        limits: AndroidHeartProductionPersonaLimits = AndroidHeartProductionPersonaLimits()
    ): AndroidHeartProductionPersonaCreateResult {
        val selfComposition = SelfComposition(foundation)
        val personalityComposition = PersonalityComposition(foundation)
        return createInternal(
            definition = definition,
            limits = limits,
            self = object : AndroidHeartProductionPersonaSelfPort {
                override fun install(identity: SelfIdentity): SelfInstallResult =
                    selfComposition.install(identity)
                override fun inspect(): SelfIdentitySnapshot? = selfComposition.inspect()
            },
            personality = object : AndroidHeartProductionPersonaPersonalityPort {
                override fun install(profile: PersonalityProfile): PersonalityInstallResult =
                    personalityComposition.install(profile)
                override fun inspect(id: PersonalityProfileId): PersonalityProfileSnapshot? =
                    personalityComposition.inspect(id)
                override fun snapshotEntries(): List<PersonalityProfileSnapshot> =
                    personalityComposition.snapshotEntries()
            }
        )
    }

    fun createDurable(
        foundation: FoundationComposition,
        definition: AndroidHeartProductionPersonaDefinition,
        persistentPersonality: EncryptedPersistentPersonalityComposition,
        limits: AndroidHeartProductionPersonaLimits = AndroidHeartProductionPersonaLimits()
    ): AndroidHeartProductionPersonaCreateResult {
        val selfComposition = SelfComposition(foundation)
        return createInternal(
            definition = definition,
            limits = limits,
            self = object : AndroidHeartProductionPersonaSelfPort {
                override fun install(identity: SelfIdentity): SelfInstallResult =
                    selfComposition.install(identity)
                override fun inspect(): SelfIdentitySnapshot? = selfComposition.inspect()
            },
            personality = object : AndroidHeartProductionPersonaPersonalityPort {
                override fun install(profile: PersonalityProfile): PersonalityInstallResult =
                    when (val installed = persistentPersonality.install(profile)) {
                        is PersistentPersonalityInstallResult.Installed ->
                            PersonalityInstallResult.Installed(
                                object : pro.liliya.core.personality.PersonalityOwnership {
                                    override val profile: PersonalityProfile =
                                        installed.ownership.profile
                                    override val generation: PersonalityGeneration =
                                        installed.ownership.generation
                                    override fun remove(): Boolean =
                                        installed.ownership.remove() ==
                                            PersistentPersonalityMutationResult.Committed
                                }
                            )
                        is PersistentPersonalityInstallResult.Rejected ->
                            PersonalityInstallResult.Rejected(installed.reason)
                        is PersistentPersonalityInstallResult.Failed ->
                            PersonalityInstallResult.Rejected(installed.reason)
                    }

                override fun inspect(id: PersonalityProfileId): PersonalityProfileSnapshot? =
                    persistentPersonality.inspect(id)

                override fun snapshotEntries(): List<PersonalityProfileSnapshot> =
                    persistentPersonality.snapshotEntries()
            },
            reuseExistingPersonality = true
        )
    }

    internal fun createInternal(
        definition: AndroidHeartProductionPersonaDefinition,
        limits: AndroidHeartProductionPersonaLimits = AndroidHeartProductionPersonaLimits(),
        self: AndroidHeartProductionPersonaSelfPort,
        personality: AndroidHeartProductionPersonaPersonalityPort,
        reuseExistingPersonality: Boolean = false
    ): AndroidHeartProductionPersonaCreateResult {
        if (!definitionWithinLimits(definition, limits)) {
            return rejected(AndroidHeartProductionPersonaCreateFailure.DEFINITION_REJECTED)
        }

        val installedSelf = when (
            val result = self.install(
                SelfIdentity(
                    id = definition.selfIdentityId,
                    name = definition.selfName,
                    origin = SelfOrigin.Declared(
                        sourceId = definition.selfSourceId,
                        sourceReference = definition.selfSourceReference
                    ),
                    createdAt = definition.selfCreatedAt
                )
            )
        ) {
            is SelfInstallResult.Installed -> {
                val snapshot = self.inspect()
                    ?: return rejected(
                        AndroidHeartProductionPersonaCreateFailure.SELF_SNAPSHOT_MISMATCH
                    )
                if (
                    snapshot.identity != result.ownership.identity ||
                    snapshot.generation != result.ownership.generation
                ) {
                    return rejected(
                        AndroidHeartProductionPersonaCreateFailure.SELF_SNAPSHOT_MISMATCH
                    )
                }
                snapshot
            }

            is SelfInstallResult.Rejected ->
                return rejected(AndroidHeartProductionPersonaCreateFailure.SELF_INSTALL_REJECTED)
        }

        val expectedProfile = try {
            PersonalityProfile(
                id = definition.personalityProfileId,
                target = PersonalityTarget.Self(
                    identityId = installedSelf.identity.id,
                    generation = installedSelf.generation
                ),
                attributes = definition.personalityAttributes,
                provenance = PersonalityProvenance(
                    sourceId = definition.personalitySourceId,
                    sourceReference = definition.personalitySourceReference
                ),
                createdAt = definition.personalityCreatedAt
            )
        } catch (_: IllegalArgumentException) {
            return rejected(AndroidHeartProductionPersonaCreateFailure.DEFINITION_REJECTED)
        }

        val restoredPersonality =
            if (reuseExistingPersonality) personality.inspect(expectedProfile.id) else null
        val installedPersonality = if (restoredPersonality != null) {
            if (
                restoredPersonality.profile != expectedProfile ||
                restoredPersonality.profile.target != PersonalityTarget.Self(
                    installedSelf.identity.id,
                    installedSelf.generation
                )
            ) {
                return rejected(
                    AndroidHeartProductionPersonaCreateFailure.PERSONALITY_SNAPSHOT_MISMATCH
                )
            }
            restoredPersonality
        } else {
            when (val result = personality.install(expectedProfile)) {
                is PersonalityInstallResult.Installed -> {
                    val snapshot = personality.inspect(expectedProfile.id)
                        ?: return rejected(
                            AndroidHeartProductionPersonaCreateFailure.PERSONALITY_SNAPSHOT_MISMATCH
                        )
                    if (
                        snapshot.profile != result.ownership.profile ||
                        snapshot.generation != result.ownership.generation ||
                        snapshot.profile.target != PersonalityTarget.Self(
                            installedSelf.identity.id,
                            installedSelf.generation
                        )
                    ) {
                        return rejected(
                            AndroidHeartProductionPersonaCreateFailure.PERSONALITY_SNAPSHOT_MISMATCH
                        )
                    }
                    snapshot
                }

                is PersonalityInstallResult.Rejected ->
                    return rejected(
                        AndroidHeartProductionPersonaCreateFailure.PERSONALITY_INSTALL_REJECTED
                    )
            }
        }

        return AndroidHeartProductionPersonaCreateResult.Ready(
            AndroidHeartProductionPersonaComposition(
                self = self,
                personality = personality,
                installedSelf = installedSelf,
                installedPersonality = installedPersonality
            )
        )
    }

    private fun definitionWithinLimits(
        definition: AndroidHeartProductionPersonaDefinition,
        limits: AndroidHeartProductionPersonaLimits
    ): Boolean {
        if (definition.selfIdentityId.value.length > limits.maxSelfIdentityIdChars) return false
        if (definition.selfName.value.length > limits.maxSelfNameChars) return false
        if (definition.selfSourceId.value.length > limits.maxSourceIdChars) return false
        if (
            definition.selfSourceReference?.value?.length?.let {
                it > limits.maxSourceReferenceChars
            } == true
        ) return false
        if (
            definition.personalityProfileId.value.length >
            limits.maxPersonalityProfileIdChars
        ) return false
        if (definition.personalityAttributes.size > limits.maxPersonalityAttributes) return false
        if (
            definition.personalityAttributes.any {
                it.key.value.length > limits.maxPersonalityAttributeKeyChars ||
                    it.value.value.length > limits.maxPersonalityAttributeValueChars
            }
        ) return false
        if (definition.personalitySourceId.value.length > limits.maxSourceIdChars) return false
        if (
            definition.personalitySourceReference?.value?.length?.let {
                it > limits.maxSourceReferenceChars
            } == true
        ) return false
        return true
    }

    private fun rejected(
        reason: AndroidHeartProductionPersonaCreateFailure
    ): AndroidHeartProductionPersonaCreateResult.Rejected =
        AndroidHeartProductionPersonaCreateResult.Rejected(reason)
}
