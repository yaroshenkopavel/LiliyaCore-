package pro.liliya.android.runtime

import pro.liliya.core.protectedmodel.LargeProtectedModelEncryptedSegmentSource
import pro.liliya.core.protectedmodel.LargeProtectedModelPackageEnvelope
import pro.liliya.core.protectedmodel.LargeProtectedModelSegmentedOpenResult
import pro.liliya.core.protectedmodel.LargeProtectedModelSegmentedPayloadLoader
import pro.liliya.core.protectedmodel.LargeProtectedModelStagedSourceOwnership
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingAbortResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingAppendResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingCleanupOutcome
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingCleanupStatus
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingCoordinator
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingPublishResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingRequest
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingSession
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingStartResult
import pro.liliya.core.protectedmodel.ProtectedModelReference

internal fun interface ProductGenerationSegmentedOpenPort {
    fun open(
        envelope: LargeProtectedModelPackageEnvelope,
        source: LargeProtectedModelEncryptedSegmentSource,
        consumer: (
            model: ProtectedModelReference,
            segmentIndex: Int,
            plaintext: ByteArray
        ) -> Unit
    ): LargeProtectedModelSegmentedOpenResult
}

enum class ProductGenerationStagingProvisionFailure {
    STAGING_START_REJECTED,
    STAGING_START_FAILED,
    PACKAGE_REJECTED,
    PACKAGE_FAILED,
    STAGING_APPEND_REJECTED,
    STAGING_APPEND_FAILED,
    COMPLETION_MISMATCH,
    STAGING_PUBLISH_REJECTED,
    STAGING_PUBLISH_FAILED,
    INTERNAL_FAILURE
}

sealed interface ProductGenerationStagingProvisionResult {
    data class Ready(
        val ownership: LargeProtectedModelStagedSourceOwnership
    ) : ProductGenerationStagingProvisionResult

    data class Rejected(
        val reason: ProductGenerationStagingProvisionFailure,
        val cleanup: LargeProtectedModelStagingCleanupOutcome? = null
    ) : ProductGenerationStagingProvisionResult
}

/**
 * Canonical product-level bridge from an already-authorized segmented protected-model package
 * into exact app-private staged-source ownership.
 *
 * Provisioning != Acquisition.
 * Provisioning != Model Selection.
 * Provisioning != License.
 * Provisioning != Authority.
 * Staged Ownership != Engine Session.
 */
class ProductGenerationStagingProvisioner internal constructor(
    private val openPort: ProductGenerationSegmentedOpenPort,
    private val staging: LargeProtectedModelStagingCoordinator
) {
    constructor(
        loader: LargeProtectedModelSegmentedPayloadLoader,
        staging: LargeProtectedModelStagingCoordinator
    ) : this(
        openPort = ProductGenerationSegmentedOpenPort { envelope, source, consumer ->
            loader.open(envelope, source) { model, segmentIndex, plaintext ->
                consumer(model, segmentIndex, plaintext)
            }
        },
        staging = staging
    )

    fun provision(
        envelope: LargeProtectedModelPackageEnvelope,
        source: LargeProtectedModelEncryptedSegmentSource
    ): ProductGenerationStagingProvisionResult {
        val payload = envelope.manifest.payload
        val request = LargeProtectedModelStagingRequest(
            model = payload.model,
            profile = payload.profile,
            expectedPlaintextBytes = payload.totalPlaintextSizeBytes,
            expectedSegmentCount = payload.segmentCount
        )

        val session = try {
            when (val started = staging.start(request)) {
                is LargeProtectedModelStagingStartResult.Started -> started.session
                is LargeProtectedModelStagingStartResult.Rejected ->
                    return ProductGenerationStagingProvisionResult.Rejected(
                        ProductGenerationStagingProvisionFailure.STAGING_START_REJECTED
                    )
                is LargeProtectedModelStagingStartResult.Failed ->
                    return ProductGenerationStagingProvisionResult.Rejected(
                        ProductGenerationStagingProvisionFailure.STAGING_START_FAILED
                    )
            }
        } catch (_: Exception) {
            return ProductGenerationStagingProvisionResult.Rejected(
                ProductGenerationStagingProvisionFailure.INTERNAL_FAILURE
            )
        }

        var callbackFailure: ProductGenerationStagingProvisionFailure? = null
        var callbackCleanup: LargeProtectedModelStagingCleanupOutcome? = null
        var callbackRequiresAbort = false

        val opened = try {
            openPort.open(envelope, source) { model, segmentIndex, plaintext ->
                if (model != request.model) {
                    callbackFailure =
                        ProductGenerationStagingProvisionFailure.COMPLETION_MISMATCH
                    callbackRequiresAbort = true
                    throw SegmentAbortSignal
                }

                when (val appended = session.append(segmentIndex, plaintext)) {
                    is LargeProtectedModelStagingAppendResult.Appended -> Unit
                    is LargeProtectedModelStagingAppendResult.Rejected -> {
                        callbackFailure =
                            ProductGenerationStagingProvisionFailure.STAGING_APPEND_REJECTED
                        callbackCleanup = appended.cleanup
                        throw SegmentAbortSignal
                    }
                    is LargeProtectedModelStagingAppendResult.Failed -> {
                        callbackFailure =
                            ProductGenerationStagingProvisionFailure.STAGING_APPEND_FAILED
                        callbackCleanup = appended.cleanup
                        throw SegmentAbortSignal
                    }
                }
            }
        } catch (_: SegmentAbortSignalMarker) {
            null
        } catch (_: Exception) {
            return rejectWithAbort(
                session = session,
                reason = ProductGenerationStagingProvisionFailure.INTERNAL_FAILURE
            )
        }

        callbackFailure?.let { reason ->
            return if (callbackRequiresAbort) {
                rejectWithAbort(session, reason)
            } else {
                ProductGenerationStagingProvisionResult.Rejected(
                    reason = reason,
                    cleanup = callbackCleanup
                )
            }
        }

        val completed = when (opened) {
            is LargeProtectedModelSegmentedOpenResult.Completed -> opened
            is LargeProtectedModelSegmentedOpenResult.Rejected ->
                return rejectWithAbort(
                    session,
                    ProductGenerationStagingProvisionFailure.PACKAGE_REJECTED
                )
            is LargeProtectedModelSegmentedOpenResult.Failed ->
                return rejectWithAbort(
                    session,
                    ProductGenerationStagingProvisionFailure.PACKAGE_FAILED
                )
            null ->
                return rejectWithAbort(
                    session,
                    ProductGenerationStagingProvisionFailure.INTERNAL_FAILURE
                )
        }

        if (
            completed.model != request.model ||
            completed.segmentCount != request.expectedSegmentCount ||
            completed.plaintextBytes != request.expectedPlaintextBytes
        ) {
            return rejectWithAbort(
                session,
                ProductGenerationStagingProvisionFailure.COMPLETION_MISMATCH
            )
        }

        return try {
            when (val published = session.sealAndPublish()) {
                is LargeProtectedModelStagingPublishResult.Published ->
                    ProductGenerationStagingProvisionResult.Ready(
                        published.ownership
                    )
                is LargeProtectedModelStagingPublishResult.Rejected ->
                    ProductGenerationStagingProvisionResult.Rejected(
                        reason =
                            ProductGenerationStagingProvisionFailure.STAGING_PUBLISH_REJECTED,
                        cleanup = published.cleanup
                    )
                is LargeProtectedModelStagingPublishResult.Failed ->
                    ProductGenerationStagingProvisionResult.Rejected(
                        reason =
                            ProductGenerationStagingProvisionFailure.STAGING_PUBLISH_FAILED,
                        cleanup = published.cleanup
                    )
            }
        } catch (_: Exception) {
            rejectWithAbort(
                session = session,
                reason = ProductGenerationStagingProvisionFailure.INTERNAL_FAILURE
            )
        }
    }

    private fun rejectWithAbort(
        session: LargeProtectedModelStagingSession,
        reason: ProductGenerationStagingProvisionFailure
    ): ProductGenerationStagingProvisionResult.Rejected {
        val cleanup = try {
            when (val aborted = session.abort()) {
                is LargeProtectedModelStagingAbortResult.Aborted -> aborted.cleanup
                is LargeProtectedModelStagingAbortResult.Rejected ->
                    LargeProtectedModelStagingCleanupOutcome(
                        LargeProtectedModelStagingCleanupStatus.REJECTED
                    )
            }
        } catch (_: Exception) {
            LargeProtectedModelStagingCleanupOutcome(
                LargeProtectedModelStagingCleanupStatus.FAILED
            )
        }
        return ProductGenerationStagingProvisionResult.Rejected(
            reason = reason,
            cleanup = cleanup
        )
    }

    private object SegmentAbortSignal : SegmentAbortSignalMarker()

    private open class SegmentAbortSignalMarker :
        RuntimeException(null, null, false, false)
}
