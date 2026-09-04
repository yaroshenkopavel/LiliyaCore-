package pro.liliya.android.semanticprovider

internal enum class OfflineSemanticProviderLifecycle {
    EMPTY,
    LOADING,
    READY,
    FAILED,
    CLOSING,
    CLOSED
}

internal sealed interface SemanticProviderSessionLoadResult {
    data class Loaded(val session: SemanticProviderEmbeddingSession) : SemanticProviderSessionLoadResult
    data object ResourceRejected : SemanticProviderSessionLoadResult
    data object Unsupported : SemanticProviderSessionLoadResult
    data object Rejected : SemanticProviderSessionLoadResult
    data object ProviderFailed : SemanticProviderSessionLoadResult
}

internal interface SemanticProviderEmbeddingSession {
    fun embed(preparedText: String): SemanticEmbeddingResult
    fun close(): SemanticEmbeddingCloseResult
}

internal fun interface SemanticProviderSessionLoader {
    fun load(artifact: ValidatedSemanticModelArtifact): SemanticProviderSessionLoadResult
}

internal class NativeSemanticProviderSessionLoader(
    private val loader: SemanticEmbeddingSessionLoader = SemanticEmbeddingSessionLoader()
) : SemanticProviderSessionLoader {
    override fun load(artifact: ValidatedSemanticModelArtifact): SemanticProviderSessionLoadResult =
        when (val result = loader.load(artifact)) {
            is SemanticEmbeddingSessionLoadResult.Loaded -> SemanticProviderSessionLoadResult.Loaded(
                NativeSemanticProviderEmbeddingSession(result.session)
            )
            SemanticEmbeddingSessionLoadResult.ResourceRejected -> SemanticProviderSessionLoadResult.ResourceRejected
            SemanticEmbeddingSessionLoadResult.Unsupported -> SemanticProviderSessionLoadResult.Unsupported
            SemanticEmbeddingSessionLoadResult.Rejected,
            SemanticEmbeddingSessionLoadResult.ModelLoadRejectedDiagnostic,
            SemanticEmbeddingSessionLoadResult.ContextInitRejectedDiagnostic -> SemanticProviderSessionLoadResult.Rejected
            SemanticEmbeddingSessionLoadResult.ProviderFailed -> SemanticProviderSessionLoadResult.ProviderFailed
        }
}

private class NativeSemanticProviderEmbeddingSession(
    private val ownership: SemanticEmbeddingSessionOwnership
) : SemanticProviderEmbeddingSession {
    override fun embed(preparedText: String): SemanticEmbeddingResult = ownership.embed(preparedText)
    override fun close(): SemanticEmbeddingCloseResult = ownership.close()
}

internal sealed interface OfflineSemanticProviderLoadResult {
    data object Ready : OfflineSemanticProviderLoadResult
    data object Busy : OfflineSemanticProviderLoadResult
    data object ResourceRejected : OfflineSemanticProviderLoadResult
    data object Unsupported : OfflineSemanticProviderLoadResult
    data object Rejected : OfflineSemanticProviderLoadResult
    data object ProviderFailed : OfflineSemanticProviderLoadResult
}

internal data class SemanticSourceObservation(
    val source: SemanticIndexSourceReference,
    val content: String
) {
    init {
        require(content.isNotBlank()) { "semantic source observation content must not be blank" }
    }

    override fun toString(): String =
        "SemanticSourceObservation(source=$source, content=<redacted:${content.length}>)"
}

internal sealed interface OfflineSemanticRebuildResult {
    data class Published(val entryCount: Int) : OfflineSemanticRebuildResult
    data object Busy : OfflineSemanticRebuildResult
    data object NotReady : OfflineSemanticRebuildResult
    data object ResourceRejected : OfflineSemanticRebuildResult
    data object EmbeddingRejected : OfflineSemanticRebuildResult
    data object EmbeddingFailed : OfflineSemanticRebuildResult
    data object SessionFailed : OfflineSemanticRebuildResult
    data object IndexRejected : OfflineSemanticRebuildResult
}

internal sealed interface OfflineSemanticAddResult {
    data object Indexed : OfflineSemanticAddResult
    data object Busy : OfflineSemanticAddResult
    data object NotReady : OfflineSemanticAddResult
    data object ResourceRejected : OfflineSemanticAddResult
    data object EmbeddingRejected : OfflineSemanticAddResult
    data object EmbeddingFailed : OfflineSemanticAddResult
    data object SessionFailed : OfflineSemanticAddResult
    data object DuplicateExact : OfflineSemanticAddResult
    data object EntityAlreadyIndexed : OfflineSemanticAddResult
    data object CapacityRejected : OfflineSemanticAddResult
}

internal sealed interface OfflineSemanticReplaceResult {
    data object Replaced : OfflineSemanticReplaceResult
    data object Busy : OfflineSemanticReplaceResult
    data object NotReady : OfflineSemanticReplaceResult
    data object ResourceRejected : OfflineSemanticReplaceResult
    data object EmbeddingRejected : OfflineSemanticReplaceResult
    data object EmbeddingFailed : OfflineSemanticReplaceResult
    data object SessionFailed : OfflineSemanticReplaceResult
    data object StaleExpected : OfflineSemanticReplaceResult
    data object IdentityMismatch : OfflineSemanticReplaceResult
    data object NonForwardGeneration : OfflineSemanticReplaceResult
}

internal sealed interface OfflineSemanticRemoveResult {
    data object Removed : OfflineSemanticRemoveResult
    data object Busy : OfflineSemanticRemoveResult
    data object NotReady : OfflineSemanticRemoveResult
    data object StaleOrMissing : OfflineSemanticRemoveResult
}

internal sealed interface OfflineSemanticProviderCloseResult {
    data object Closed : OfflineSemanticProviderCloseResult
    data object Busy : OfflineSemanticProviderCloseResult
    data object AlreadyClosed : OfflineSemanticProviderCloseResult
    data object ProviderFailed : OfflineSemanticProviderCloseResult
}

/**
 * Owns one embedding session and one published advisory semantic index generation.
 *
 * Rebuild is explicit caller work. Discovery is structurally unavailable while rebuild is active,
 * and no partial replacement index is published. Fatal embedding/session failure poisons this
 * composition until explicit close; there is no hidden reload or retry.
 */
internal class OfflineSemanticProviderComposition(
    private val profileGeneration: SemanticProfileGeneration,
    private val sessionLoader: SemanticProviderSessionLoader = NativeSemanticProviderSessionLoader(),
    private val limits: SemanticFlatIndexLimits = SemanticFlatIndexLimits()
) : SemanticCandidateDiscoveryPort {
    private val publication = SemanticIndexPublication(profileGeneration, limits)

    @Volatile
    private var lifecycle: OfflineSemanticProviderLifecycle = OfflineSemanticProviderLifecycle.EMPTY

    @Volatile
    private var rebuilding: Boolean = false

    @Volatile
    private var operationInFlight: Boolean = false

    @Volatile
    private var indexPublished: Boolean = false

    private var session: SemanticProviderEmbeddingSession? = null

    fun lifecycle(): OfflineSemanticProviderLifecycle = lifecycle

    @Synchronized
    fun load(artifact: ValidatedSemanticModelArtifact): OfflineSemanticProviderLoadResult {
        if (lifecycle != OfflineSemanticProviderLifecycle.EMPTY) {
            return OfflineSemanticProviderLoadResult.Busy
        }
        if (artifact.spec.profileGeneration != profileGeneration) {
            lifecycle = OfflineSemanticProviderLifecycle.FAILED
            return OfflineSemanticProviderLoadResult.Rejected
        }

        lifecycle = OfflineSemanticProviderLifecycle.LOADING
        return when (val loaded = try {
            sessionLoader.load(artifact)
        } catch (_: Exception) {
            SemanticProviderSessionLoadResult.ProviderFailed
        }) {
            is SemanticProviderSessionLoadResult.Loaded -> {
                session = loaded.session
                lifecycle = OfflineSemanticProviderLifecycle.READY
                OfflineSemanticProviderLoadResult.Ready
            }
            SemanticProviderSessionLoadResult.ResourceRejected -> failLoad(
                OfflineSemanticProviderLoadResult.ResourceRejected
            )
            SemanticProviderSessionLoadResult.Unsupported -> failLoad(
                OfflineSemanticProviderLoadResult.Unsupported
            )
            SemanticProviderSessionLoadResult.Rejected -> failLoad(
                OfflineSemanticProviderLoadResult.Rejected
            )
            SemanticProviderSessionLoadResult.ProviderFailed -> failLoad(
                OfflineSemanticProviderLoadResult.ProviderFailed
            )
        }
    }

    fun rebuild(observations: List<SemanticSourceObservation>): OfflineSemanticRebuildResult {
        if (observations.size > limits.maxTotalEntries) {
            return OfflineSemanticRebuildResult.IndexRejected
        }

        val activeSession = synchronized(this) {
            if (lifecycle != OfflineSemanticProviderLifecycle.READY) {
                return OfflineSemanticRebuildResult.NotReady
            }
            if (rebuilding || operationInFlight) return OfflineSemanticRebuildResult.Busy
            val current = session ?: run {
                lifecycle = OfflineSemanticProviderLifecycle.FAILED
                return OfflineSemanticRebuildResult.SessionFailed
            }
            rebuilding = true
            current
        }

        val seeds = ArrayList<SemanticIndexSeed>(observations.size)
        var terminal: OfflineSemanticRebuildResult? = null
        try {
            for (observation in observations) {
                val prepared = when (val preparation = SemanticTextProfile.preparePassage(observation.content)) {
                    is SemanticPreparedTextResult.Prepared -> preparation.text
                    SemanticPreparedTextResult.RequestRejected -> {
                        terminal = OfflineSemanticRebuildResult.EmbeddingRejected
                        break
                    }
                    SemanticPreparedTextResult.ResourceRejected -> {
                        terminal = OfflineSemanticRebuildResult.ResourceRejected
                        break
                    }
                }

                when (val embedding = activeSession.embed(prepared)) {
                    is SemanticEmbeddingResult.Embedded -> seeds += SemanticIndexSeed(
                        source = observation.source,
                        vector = embedding.vector
                    )
                    SemanticEmbeddingResult.ResourceRejected -> {
                        terminal = OfflineSemanticRebuildResult.ResourceRejected
                        break
                    }
                    SemanticEmbeddingResult.RequestRejected -> {
                        terminal = OfflineSemanticRebuildResult.EmbeddingRejected
                        break
                    }
                    SemanticEmbeddingResult.StaleSession -> {
                        terminal = poison(OfflineSemanticRebuildResult.SessionFailed)
                        break
                    }
                    SemanticEmbeddingResult.OperationFailed,
                    SemanticEmbeddingResult.ProviderFailed -> {
                        terminal = poison(OfflineSemanticRebuildResult.EmbeddingFailed)
                        break
                    }
                }
            }

            if (terminal != null) return terminal

            return when (val rebuilt = publication.rebuild(seeds)) {
                is SemanticIndexRebuildResult.Published -> {
                    synchronized(this) {
                        if (lifecycle != OfflineSemanticProviderLifecycle.READY) {
                            return OfflineSemanticRebuildResult.SessionFailed
                        }
                        indexPublished = true
                    }
                    OfflineSemanticRebuildResult.Published(rebuilt.entryCount)
                }
                SemanticIndexRebuildResult.DuplicateOrConflictingIdentity,
                SemanticIndexRebuildResult.CapacityRejected -> OfflineSemanticRebuildResult.IndexRejected
            }
        } catch (_: Exception) {
            return poison(OfflineSemanticRebuildResult.EmbeddingFailed)
        } finally {
            synchronized(this) { rebuilding = false }
        }
    }

    fun add(observation: SemanticSourceObservation): OfflineSemanticAddResult {
        val activeSession = synchronized(this) {
            if (lifecycle != OfflineSemanticProviderLifecycle.READY) {
                return OfflineSemanticAddResult.NotReady
            }
            if (rebuilding || operationInFlight) return OfflineSemanticAddResult.Busy
            val current = session ?: run {
                lifecycle = OfflineSemanticProviderLifecycle.FAILED
                return OfflineSemanticAddResult.SessionFailed
            }
            operationInFlight = true
            current
        }

        try {
            val prepared = when (val preparation = SemanticTextProfile.preparePassage(observation.content)) {
                is SemanticPreparedTextResult.Prepared -> preparation.text
                SemanticPreparedTextResult.RequestRejected ->
                    return OfflineSemanticAddResult.EmbeddingRejected
                SemanticPreparedTextResult.ResourceRejected ->
                    return OfflineSemanticAddResult.ResourceRejected
            }
            return when (val embedding = activeSession.embed(prepared)) {
                is SemanticEmbeddingResult.Embedded -> when (
                    publication.addExact(observation.source, embedding.vector)
                ) {
                    SemanticIndexAddResult.Indexed -> {
                        synchronized(this) { indexPublished = true }
                        OfflineSemanticAddResult.Indexed
                    }
                    SemanticIndexAddResult.DuplicateExact -> OfflineSemanticAddResult.DuplicateExact
                    SemanticIndexAddResult.EntityAlreadyIndexed ->
                        OfflineSemanticAddResult.EntityAlreadyIndexed
                    SemanticIndexAddResult.CapacityRejected ->
                        OfflineSemanticAddResult.CapacityRejected
                }
                SemanticEmbeddingResult.ResourceRejected -> OfflineSemanticAddResult.ResourceRejected
                SemanticEmbeddingResult.RequestRejected -> OfflineSemanticAddResult.EmbeddingRejected
                SemanticEmbeddingResult.StaleSession -> poisonAdd(OfflineSemanticAddResult.SessionFailed)
                SemanticEmbeddingResult.OperationFailed,
                SemanticEmbeddingResult.ProviderFailed ->
                    poisonAdd(OfflineSemanticAddResult.EmbeddingFailed)
            }
        } catch (_: Exception) {
            return poisonAdd(OfflineSemanticAddResult.EmbeddingFailed)
        } finally {
            synchronized(this) { operationInFlight = false }
        }
    }

    fun replace(
        expected: SemanticIndexSourceReference,
        replacement: SemanticSourceObservation
    ): OfflineSemanticReplaceResult {
        val activeSession = synchronized(this) {
            if (lifecycle != OfflineSemanticProviderLifecycle.READY) {
                return OfflineSemanticReplaceResult.NotReady
            }
            if (rebuilding || operationInFlight) return OfflineSemanticReplaceResult.Busy
            val current = session ?: run {
                lifecycle = OfflineSemanticProviderLifecycle.FAILED
                return OfflineSemanticReplaceResult.SessionFailed
            }
            operationInFlight = true
            current
        }

        try {
            val prepared = when (val preparation = SemanticTextProfile.preparePassage(replacement.content)) {
                is SemanticPreparedTextResult.Prepared -> preparation.text
                SemanticPreparedTextResult.RequestRejected ->
                    return OfflineSemanticReplaceResult.EmbeddingRejected
                SemanticPreparedTextResult.ResourceRejected ->
                    return OfflineSemanticReplaceResult.ResourceRejected
            }
            return when (val embedding = activeSession.embed(prepared)) {
                is SemanticEmbeddingResult.Embedded -> when (
                    publication.replaceExact(expected, replacement.source, embedding.vector)
                ) {
                    SemanticIndexReplaceResult.Replaced -> OfflineSemanticReplaceResult.Replaced
                    SemanticIndexReplaceResult.StaleExpected ->
                        OfflineSemanticReplaceResult.StaleExpected
                    SemanticIndexReplaceResult.IdentityMismatch ->
                        OfflineSemanticReplaceResult.IdentityMismatch
                    SemanticIndexReplaceResult.NonForwardGeneration ->
                        OfflineSemanticReplaceResult.NonForwardGeneration
                }
                SemanticEmbeddingResult.ResourceRejected -> OfflineSemanticReplaceResult.ResourceRejected
                SemanticEmbeddingResult.RequestRejected -> OfflineSemanticReplaceResult.EmbeddingRejected
                SemanticEmbeddingResult.StaleSession ->
                    poisonReplace(OfflineSemanticReplaceResult.SessionFailed)
                SemanticEmbeddingResult.OperationFailed,
                SemanticEmbeddingResult.ProviderFailed ->
                    poisonReplace(OfflineSemanticReplaceResult.EmbeddingFailed)
            }
        } catch (_: Exception) {
            return poisonReplace(OfflineSemanticReplaceResult.EmbeddingFailed)
        } finally {
            synchronized(this) { operationInFlight = false }
        }
    }

    fun remove(source: SemanticIndexSourceReference): OfflineSemanticRemoveResult {
        synchronized(this) {
            if (lifecycle != OfflineSemanticProviderLifecycle.READY) {
                return OfflineSemanticRemoveResult.NotReady
            }
            if (rebuilding || operationInFlight) return OfflineSemanticRemoveResult.Busy
            if (session == null) {
                lifecycle = OfflineSemanticProviderLifecycle.FAILED
                return OfflineSemanticRemoveResult.NotReady
            }
            operationInFlight = true
        }
        return try {
            when (publication.removeExact(source)) {
                SemanticIndexRemoveResult.Removed -> OfflineSemanticRemoveResult.Removed
                SemanticIndexRemoveResult.StaleOrMissing -> OfflineSemanticRemoveResult.StaleOrMissing
            }
        } finally {
            synchronized(this) { operationInFlight = false }
        }
    }

    override fun discover(
        domain: SemanticIndexDomain,
        input: String,
        maxCandidates: Int
    ): SemanticCandidateDiscoveryResult {
        if (maxCandidates <= 0) {
            return SemanticProviderFailure(SemanticProviderFailureKind.REQUEST_REJECTED)
        }
        val domainCandidateCapacity = when (domain) {
            SemanticIndexDomain.MEMORY -> limits.maxMemoryEntries
            SemanticIndexDomain.KNOWLEDGE -> limits.maxKnowledgeEntries
        }
        if (maxCandidates > domainCandidateCapacity) {
            return SemanticProviderFailure(SemanticProviderFailureKind.RESOURCE_REJECTED)
        }

        val activeSession = synchronized(this) {
            when {
                lifecycle == OfflineSemanticProviderLifecycle.CLOSED ||
                    lifecycle == OfflineSemanticProviderLifecycle.CLOSING ->
                    return SemanticProviderFailure(SemanticProviderFailureKind.CLOSED)
                lifecycle == OfflineSemanticProviderLifecycle.FAILED ->
                    return SemanticProviderFailure(SemanticProviderFailureKind.SESSION_FAILED)
                lifecycle != OfflineSemanticProviderLifecycle.READY ->
                    return SemanticProviderFailure(SemanticProviderFailureKind.BUSY)
                rebuilding ->
                    return SemanticProviderFailure(SemanticProviderFailureKind.INDEX_UNAVAILABLE)
                operationInFlight ->
                    return SemanticProviderFailure(SemanticProviderFailureKind.BUSY)
                !indexPublished ->
                    return SemanticProviderFailure(SemanticProviderFailureKind.INDEX_UNAVAILABLE)
                else -> {
                    val current = session ?: return SemanticProviderFailure(
                        SemanticProviderFailureKind.SESSION_FAILED
                    )
                    operationInFlight = true
                    current
                }
            }
        }

        try {
            val prepared = when (val preparation = SemanticTextProfile.prepareQuery(input)) {
                is SemanticPreparedTextResult.Prepared -> preparation.text
                SemanticPreparedTextResult.RequestRejected ->
                    return SemanticProviderFailure(SemanticProviderFailureKind.REQUEST_REJECTED)
                SemanticPreparedTextResult.ResourceRejected ->
                    return SemanticProviderFailure(SemanticProviderFailureKind.RESOURCE_REJECTED)
            }

            return try {
                when (val embedding = activeSession.embed(prepared)) {
                    is SemanticEmbeddingResult.Embedded -> SemanticCandidates(
                        publication.rank(domain, embedding.vector, maxCandidates).map { it.source }
                    )
                    SemanticEmbeddingResult.ResourceRejected ->
                        SemanticProviderFailure(SemanticProviderFailureKind.RESOURCE_REJECTED)
                    SemanticEmbeddingResult.RequestRejected ->
                        SemanticProviderFailure(SemanticProviderFailureKind.REQUEST_REJECTED)
                    SemanticEmbeddingResult.StaleSession -> {
                        poisonDiscovery(SemanticProviderFailureKind.SESSION_FAILED)
                    }
                    SemanticEmbeddingResult.OperationFailed,
                    SemanticEmbeddingResult.ProviderFailed -> {
                        poisonDiscovery(SemanticProviderFailureKind.OPERATION_FAILED)
                    }
                }
            } catch (failure: Exception) {
                synchronized(this) { lifecycle = OfflineSemanticProviderLifecycle.FAILED }
                SemanticProviderFailure(
                    kind = SemanticProviderFailureKind.PROVIDER_FAILED,
                    exceptionClass = failure.javaClass.name
                )
            }
        } finally {
            synchronized(this) { operationInFlight = false }
        }
    }

    @Synchronized
    fun close(): OfflineSemanticProviderCloseResult {
        if (lifecycle == OfflineSemanticProviderLifecycle.CLOSED) {
            return OfflineSemanticProviderCloseResult.AlreadyClosed
        }
        if (rebuilding || operationInFlight || lifecycle == OfflineSemanticProviderLifecycle.LOADING ||
            lifecycle == OfflineSemanticProviderLifecycle.CLOSING
        ) {
            return OfflineSemanticProviderCloseResult.Busy
        }

        lifecycle = OfflineSemanticProviderLifecycle.CLOSING
        val active = session
        val closeResult = if (active == null) {
            SemanticEmbeddingCloseResult.Closed
        } else {
            try {
                active.close()
            } catch (_: Exception) {
                SemanticEmbeddingCloseResult.ProviderFailed
            }
        }
        return when (closeResult) {
            SemanticEmbeddingCloseResult.Closed,
            SemanticEmbeddingCloseResult.StaleOrAlreadyClosed -> {
                session = null
                indexPublished = false
                lifecycle = OfflineSemanticProviderLifecycle.CLOSED
                OfflineSemanticProviderCloseResult.Closed
            }
            SemanticEmbeddingCloseResult.ProviderFailed -> {
                // Preserve the exact ownership handle for an explicit cleanup retry. Discovery and
                // index updates remain rejected by FAILED; there is no hidden retry or reload.
                lifecycle = OfflineSemanticProviderLifecycle.FAILED
                OfflineSemanticProviderCloseResult.ProviderFailed
            }
        }
    }

    @Synchronized
    private fun failLoad(result: OfflineSemanticProviderLoadResult): OfflineSemanticProviderLoadResult {
        session = null
        lifecycle = OfflineSemanticProviderLifecycle.FAILED
        return result
    }

    @Synchronized
    private fun poison(result: OfflineSemanticRebuildResult): OfflineSemanticRebuildResult {
        lifecycle = OfflineSemanticProviderLifecycle.FAILED
        return result
    }

    @Synchronized
    private fun poisonDiscovery(kind: SemanticProviderFailureKind): SemanticProviderFailure {
        lifecycle = OfflineSemanticProviderLifecycle.FAILED
        return SemanticProviderFailure(kind)
    }

    @Synchronized
    private fun poisonAdd(result: OfflineSemanticAddResult): OfflineSemanticAddResult {
        lifecycle = OfflineSemanticProviderLifecycle.FAILED
        return result
    }

    @Synchronized
    private fun poisonReplace(result: OfflineSemanticReplaceResult): OfflineSemanticReplaceResult {
        lifecycle = OfflineSemanticProviderLifecycle.FAILED
        return result
    }
}
