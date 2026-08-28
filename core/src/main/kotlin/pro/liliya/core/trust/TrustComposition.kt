package pro.liliya.core.trust

import pro.liliya.core.foundation.FoundationComposition

interface TrustOwnership {
    val anchor: TrustAnchor
    val generation: TrustGeneration
    fun remove(): Boolean
}

sealed interface TrustAnchorResult {
    data class Anchored(val ownership: TrustOwnership) : TrustAnchorResult
    data class Rejected(val reason: String) : TrustAnchorResult
}

class TrustComposition(
    private val foundation: FoundationComposition
) {
    private val store = TrustAnchorStore(foundation.observability)

    fun anchor(anchor: TrustAnchor): TrustAnchorResult {
        val context = foundation.rootContext(
            operation = "anchorTrust",
            component = "Trust",
            metadata = anchorMetadata(anchor)
        )
        return when (val result = store.register(anchor, context)) {
            is TrustAnchorRegistrationResult.Registered -> TrustAnchorResult.Anchored(
                ownership = object : TrustOwnership {
                    override val anchor: TrustAnchor = result.registration.anchor
                    override val generation: TrustGeneration = result.registration.generation

                    override fun remove(): Boolean = result.registration.remove(
                        foundation.rootContext(
                            operation = "removeTrustAnchor",
                            component = "Trust",
                            metadata = anchorMetadata(anchor) + mapOf(
                                "trustGeneration" to generation.value.toString()
                            )
                        )
                    )
                }
            )

            is TrustAnchorRegistrationResult.Rejected -> TrustAnchorResult.Rejected(result.reason)
        }
    }

    fun find(id: TrustAnchorId): TrustAnchor? = store.find(id)

    fun inspect(id: TrustAnchorId): TrustAnchorSnapshot? = store.inspect(id)

    fun contains(id: TrustAnchorId): Boolean = store.contains(id)

    fun snapshot(): List<TrustAnchor> = store.snapshot()

    fun snapshotEntries(): List<TrustAnchorSnapshot> = store.snapshotEntries()

    private fun anchorMetadata(anchor: TrustAnchor): Map<String, String> = buildMap {
        put("trustAnchorId", anchor.id.value)
        put("trustSourceId", anchor.provenance.sourceId.value)
        anchor.provenance.sourceReference?.let { reference ->
            put("trustSourceReference", reference.value)
        }
        when (val subject = anchor.subject) {
            is TrustSubject.Self -> {
                put("trustSubjectType", "self")
                put("selfIdentityId", subject.identityId.value)
                put("selfGeneration", subject.generation.value.toString())
            }

            is TrustSubject.Declared -> {
                put("trustSubjectType", "declared")
                put("trustSubjectId", subject.subjectId.value)
            }
        }
    }
}
