package pro.liliya.core.identity

import pro.liliya.core.foundation.FoundationComposition

interface SelfOwnership {
    val identity: SelfIdentity
    val generation: SelfGeneration
    fun remove(): Boolean
}

sealed interface SelfInstallResult {
    data class Installed(val ownership: SelfOwnership) : SelfInstallResult
    data class Rejected(val reason: String) : SelfInstallResult
}

class SelfComposition(
    private val foundation: FoundationComposition
) {
    private val store = SelfStore(foundation.observability)

    fun install(identity: SelfIdentity): SelfInstallResult {
        val context = foundation.rootContext(
            operation = "installSelf",
            component = "Self",
            metadata = originMetadata(identity)
        )
        return when (val result = store.register(identity, context)) {
            is SelfRegistrationResult.Registered -> SelfInstallResult.Installed(
                ownership = object : SelfOwnership {
                    override val identity: SelfIdentity = result.registration.identity
                    override val generation: SelfGeneration = result.registration.generation

                    override fun remove(): Boolean = result.registration.remove(
                        foundation.rootContext(
                            operation = "removeSelf",
                            component = "Self",
                            metadata = originMetadata(identity) + mapOf(
                                "selfGeneration" to generation.value.toString()
                            )
                        )
                    )
                }
            )

            is SelfRegistrationResult.Rejected -> SelfInstallResult.Rejected(result.reason)
        }
    }

    fun current(): SelfIdentity? = store.current()

    fun inspect(): SelfIdentitySnapshot? = store.inspect()

    fun isInstalled(): Boolean = store.isPresent()

    private fun originMetadata(identity: SelfIdentity): Map<String, String> = buildMap {
        put("selfIdentityId", identity.id.value)
        when (val origin = identity.origin) {
            is SelfOrigin.Knowledge -> {
                put("selfOriginType", "knowledge")
                put("knowledgeItemId", origin.itemId.value)
                put("knowledgeGeneration", origin.generation.value.toString())
            }

            is SelfOrigin.Declared -> {
                put("selfOriginType", "declared")
                put("selfSourceId", origin.sourceId.value)
                origin.sourceReference?.let { reference ->
                    put("selfSourceReference", reference.value)
                }
            }
        }
    }
}
