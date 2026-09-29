package pro.liliya.core.asf

private const val MAX_SYNTHESIS_FINDINGS = 128
private const val MAX_SYNTHESIS_TEXT = 512

@JvmInline
value class AgentClaimKey(val value: String) {
    init {
        require(value.isNotBlank()) { "claim key must not be blank" }
        require(value.length <= MAX_SYNTHESIS_TEXT) { "claim key exceeds bounded size" }
    }
}

enum class AgentReviewKind {
    VERIFICATION,
    CRITIQUE,
    ADVERSARIAL_REVIEW
}

enum class AgentFindingDisposition {
    SUPPORTS,
    CHALLENGES,
    INCONCLUSIVE
}

data class AgentReviewFinding(
    val claimKey: AgentClaimKey,
    val disposition: AgentFindingDisposition,
    val evidenceReferences: List<String>
) {
    init {
        require(evidenceReferences.isNotEmpty()) { "review finding requires evidence provenance" }
        require(evidenceReferences.size <= 64) { "too many finding evidence references" }
        require(evidenceReferences.distinct().size == evidenceReferences.size) {
            "finding evidence references must be unique"
        }
        require(evidenceReferences == evidenceReferences.sorted()) {
            "finding evidence references must use canonical order"
        }
        evidenceReferences.forEach {
            require(it.isNotBlank()) { "finding evidence reference must not be blank" }
            require(it.length <= 256) { "finding evidence reference exceeds bounded size" }
        }
    }

    companion object {
        fun create(
            claimKey: AgentClaimKey,
            disposition: AgentFindingDisposition,
            evidenceReferences: Collection<String>
        ) = AgentReviewFinding(
            claimKey = claimKey,
            disposition = disposition,
            evidenceReferences = evidenceReferences.sorted()
        )
    }
}

data class AgentReviewContribution(
    val kind: AgentReviewKind,
    val artifact: AgentArtifact,
    val findings: List<AgentReviewFinding>
) {
    init {
        require(findings.isNotEmpty()) { "review contribution requires at least one finding" }
        require(findings.size <= MAX_SYNTHESIS_FINDINGS) { "too many review findings" }
        require(findings.map { it.claimKey }.distinct().size == findings.size) {
            "review contribution may contain only one finding per claim"
        }
        require(findings == findings.sortedBy { it.claimKey.value }) {
            "review findings must use canonical claim order"
        }
        findings.forEach { finding ->
            require(finding.evidenceReferences.all { it in artifact.provenanceReferences }) {
                "finding evidence must be a subset of artifact provenance"
            }
        }
    }

    companion object {
        fun create(
            kind: AgentReviewKind,
            artifact: AgentArtifact,
            findings: Collection<AgentReviewFinding>
        ) = AgentReviewContribution(
            kind = kind,
            artifact = artifact,
            findings = findings.sortedBy { it.claimKey.value }
        )
    }
}

enum class AgentSynthesisFindingState {
    SUPPORTED,
    CHALLENGED,
    INCONCLUSIVE,
    UNRESOLVED_CONFLICT
}

data class AgentSynthesisFinding(
    val claimKey: AgentClaimKey,
    val state: AgentSynthesisFindingState,
    val contributorArtifactIds: List<AgentArtifactId>,
    val evidenceReferences: List<String>
) {
    init {
        require(contributorArtifactIds.isNotEmpty()) { "synthesis finding requires contributors" }
        require(contributorArtifactIds.distinct().size == contributorArtifactIds.size) {
            "synthesis contributor artifacts must be unique"
        }
        require(contributorArtifactIds == contributorArtifactIds.sortedBy { it.value }) {
            "synthesis contributor artifacts must use canonical order"
        }
        require(evidenceReferences.isNotEmpty()) { "synthesis finding requires evidence" }
        require(evidenceReferences.distinct().size == evidenceReferences.size) {
            "synthesis evidence references must be unique"
        }
        require(evidenceReferences == evidenceReferences.sorted()) {
            "synthesis evidence references must use canonical order"
        }
    }
}

data class AgentSynthesisResult(
    val rootTaskId: AgentRootTaskId,
    val findings: List<AgentSynthesisFinding>,
    val contributingArtifactIds: List<AgentArtifactId>
) {
    init {
        require(findings.isNotEmpty()) { "synthesis result requires findings" }
        require(findings.size <= MAX_SYNTHESIS_FINDINGS) { "too many synthesis findings" }
        require(findings == findings.sortedBy { it.claimKey.value }) {
            "synthesis findings must use canonical claim order"
        }
        require(contributingArtifactIds.isNotEmpty()) { "synthesis result requires artifacts" }
        require(contributingArtifactIds.distinct().size == contributingArtifactIds.size) {
            "synthesis result artifacts must be unique"
        }
        require(contributingArtifactIds == contributingArtifactIds.sortedBy { it.value }) {
            "synthesis result artifacts must use canonical order"
        }
        require(findings.flatMap { it.contributorArtifactIds }.all { it in contributingArtifactIds }) {
            "synthesis finding contributor must exist in result artifact set"
        }
    }
}

class AgentSynthesisGate {
    fun synthesize(contributions: Collection<AgentReviewContribution>): AgentSynthesisResult {
        require(contributions.isNotEmpty()) { "synthesis requires at least one contribution" }

        val canonical = contributions.sortedBy { it.artifact.id.value }
        require(canonical.map { it.artifact.id }.distinct().size == canonical.size) {
            "synthesis contribution artifacts must be unique"
        }

        val rootTaskId = canonical.first().artifact.rootTaskId
        require(canonical.all { it.artifact.rootTaskId == rootTaskId }) {
            "synthesis contributions must belong to one root task"
        }

        val grouped = canonical
            .flatMap { contribution ->
                contribution.findings.map { finding -> contribution to finding }
            }
            .groupBy { it.second.claimKey }

        val findings = grouped.entries
            .sortedBy { it.key.value }
            .map { (claimKey, entries) ->
                val dispositions = entries.map { it.second.disposition }.toSet()
                val state = when {
                    AgentFindingDisposition.SUPPORTS in dispositions &&
                        AgentFindingDisposition.CHALLENGES in dispositions ->
                        AgentSynthesisFindingState.UNRESOLVED_CONFLICT

                    dispositions == setOf(AgentFindingDisposition.SUPPORTS) ->
                        AgentSynthesisFindingState.SUPPORTED

                    dispositions == setOf(AgentFindingDisposition.CHALLENGES) ->
                        AgentSynthesisFindingState.CHALLENGED

                    else -> AgentSynthesisFindingState.INCONCLUSIVE
                }

                AgentSynthesisFinding(
                    claimKey = claimKey,
                    state = state,
                    contributorArtifactIds = entries
                        .map { it.first.artifact.id }
                        .distinct()
                        .sortedBy { it.value },
                    evidenceReferences = entries
                        .flatMap { it.second.evidenceReferences }
                        .distinct()
                        .sorted()
                )
            }

        return AgentSynthesisResult(
            rootTaskId = rootTaskId,
            findings = findings,
            contributingArtifactIds = canonical.map { it.artifact.id }
        )
    }
}
