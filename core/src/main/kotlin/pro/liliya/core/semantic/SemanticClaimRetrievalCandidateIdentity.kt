package pro.liliya.core.semantic

import pro.liliya.core.retrieval.RetrievalCandidateId

object SemanticClaimRetrievalCandidateIdentity {
    private const val PREFIX = "semantic-claim:"
    private const val VERSION_SEPARATOR = ":v"

    fun encode(reference: SemanticClaimVersionReference): RetrievalCandidateId =
        RetrievalCandidateId(
            PREFIX +
                reference.claimId.value +
                VERSION_SEPARATOR +
                reference.version.value
        )

    fun decode(id: RetrievalCandidateId): SemanticClaimVersionReference? {
        val raw = id.value
        if (!raw.startsWith(PREFIX)) return null
        val separator = raw.lastIndexOf(VERSION_SEPARATOR)
        if (separator <= PREFIX.length) return null
        val claimId = raw.substring(PREFIX.length, separator)
        val versionText = raw.substring(separator + VERSION_SEPARATOR.length)
        val version = versionText.toLongOrNull() ?: return null
        if (claimId.isBlank() || version <= 0L) return null
        return try {
            SemanticClaimVersionReference(
                SemanticClaimId(claimId),
                SemanticClaimVersion(version)
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}