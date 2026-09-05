package pro.liliya.core.modelengine

import pro.liliya.core.protectedmodel.LargeProtectedModelEngineSourceCapability
import pro.liliya.core.protectedmodel.LargeProtectedModelEngineUseAcquireResult
import pro.liliya.core.protectedmodel.LargeProtectedModelEngineUseLease
import pro.liliya.core.protectedmodel.LargeProtectedModelEngineUseReleaseResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagedSourceOwnership
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingCoordinator

/**
 * Parallel large-model loader seam. Unlike ModelEngineLoaderPort, this contract never receives
 * whole-model plaintext bytes or filesystem paths. A future platform adapter resolves the
 * purpose-specific capability inside its own least-authority boundary.
 */
fun interface StagedModelEngineLoaderPort {
    fun load(source: LargeProtectedModelEngineSourceCapability): ModelEngineLoadResult
}

/**
 * Coordinates staged-source use lifetime around one engine session.
 *
 * The staging coordinator lock is never held while provider load/infer/close callbacks run.
 * Load rejection/failure releases the acquired source lease. Successful load transfers that
 * lease into the returned session ownership until engine close is known to have succeeded.
 */
class StagedModelEngineLoadCoordinator(
    private val stagingCoordinator: LargeProtectedModelStagingCoordinator,
    private val loader: StagedModelEngineLoaderPort
) {
    fun load(ownership: LargeProtectedModelStagedSourceOwnership): ModelEngineLoadResult {
        val acquired = stagingCoordinator.acquireEngineUse(ownership)
        val lease = when (acquired) {
            is LargeProtectedModelEngineUseAcquireResult.Acquired -> acquired.lease
            is LargeProtectedModelEngineUseAcquireResult.Rejected ->
                return ModelEngineLoadResult.Rejected(ModelEngineLoadFailure.LOAD_REJECTED)
        }

        val providerResult = try {
            loader.load(lease.source)
        } catch (_: Throwable) {
            lease.release()
            return ModelEngineLoadResult.Rejected(ModelEngineLoadFailure.PROVIDER_FAILED)
        }

        return when (providerResult) {
            is ModelEngineLoadResult.Loaded ->
                ModelEngineLoadResult.Loaded(
                    ownership = leasedSession(providerResult.ownership, lease)
                )
            is ModelEngineLoadResult.Rejected -> {
                val released = lease.release()
                if (released is LargeProtectedModelEngineUseReleaseResult.Released) {
                    providerResult
                } else {
                    ModelEngineLoadResult.Rejected(ModelEngineLoadFailure.PROVIDER_FAILED)
                }
            }
        }
    }

    private fun leasedSession(
        delegate: ModelEngineSessionOwnership,
        lease: LargeProtectedModelEngineUseLease
    ): ModelEngineSessionOwnership {
        val lifecycle = LeasedSessionLifecycle(delegate, lease)
        return if (delegate is ModelEngineStreamingSessionOwnership) {
            object : ModelEngineStreamingSessionOwnership {
                override val backendId: ModelEngineBackendId = delegate.backendId
                override val handleId: ModelEngineHandleId = delegate.handleId

                override fun infer(
                    request: ModelEngineInferenceRequest
                ): ModelEngineInferenceResult =
                    lifecycle.infer(request)

                override fun stream(
                    request: ModelEngineInferenceRequest,
                    sink: ModelEngineStreamingSink
                ): ModelEngineInferenceResult {
                    if (!lifecycle.operationAllowed()) {
                        return ModelEngineInferenceResult.Rejected(
                            ModelEngineInferenceFailure.SESSION_FAILED
                        )
                    }
                    return delegate.stream(request, sink)
                }

                override fun close(): ModelEngineCloseResult = lifecycle.close()
            }
        } else {
            object : ModelEngineSessionOwnership {
                override val backendId: ModelEngineBackendId = delegate.backendId
                override val handleId: ModelEngineHandleId = delegate.handleId

                override fun infer(
                    request: ModelEngineInferenceRequest
                ): ModelEngineInferenceResult =
                    lifecycle.infer(request)

                override fun close(): ModelEngineCloseResult = lifecycle.close()
            }
        }
    }

    private class LeasedSessionLifecycle(
        private val delegate: ModelEngineSessionOwnership,
        private val lease: LargeProtectedModelEngineUseLease
    ) {
        private val closeLock = Any()
        private var engineClosed = false
        private var fullyClosed = false

        fun operationAllowed(): Boolean = synchronized(closeLock) {
            !engineClosed && !fullyClosed
        }

        fun infer(request: ModelEngineInferenceRequest): ModelEngineInferenceResult {
            if (!operationAllowed()) {
                return ModelEngineInferenceResult.Rejected(
                    ModelEngineInferenceFailure.SESSION_FAILED
                )
            }
            return delegate.infer(request)
        }

        fun close(): ModelEngineCloseResult = synchronized(closeLock) {
            if (fullyClosed) return@synchronized ModelEngineCloseResult.Closed

            if (!engineClosed) {
                val engineResult = try {
                    delegate.close()
                } catch (_: Throwable) {
                    return@synchronized ModelEngineCloseResult.Failed(
                        ModelEngineCloseFailure.PROVIDER_FAILED
                    )
                }
                when (engineResult) {
                    ModelEngineCloseResult.Closed -> engineClosed = true
                    is ModelEngineCloseResult.Failed -> return@synchronized engineResult
                }
            }

            when (lease.release()) {
                LargeProtectedModelEngineUseReleaseResult.Released -> {
                    fullyClosed = true
                    ModelEngineCloseResult.Closed
                }
                is LargeProtectedModelEngineUseReleaseResult.Rejected ->
                    ModelEngineCloseResult.Failed(ModelEngineCloseFailure.CLOSE_FAILED)
            }
        }
    }

}
