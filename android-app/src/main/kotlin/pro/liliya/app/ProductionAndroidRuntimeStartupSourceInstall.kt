package pro.liliya.app

import pro.liliya.android.runtime.AndroidProductRuntimeStartupRequestSource
import pro.liliya.android.runtime.AndroidProductRuntimeStartupRequestSourceFailure
import pro.liliya.android.runtime.AndroidProductRuntimeStartupRequestSourceInput
import pro.liliya.android.runtime.AndroidProductRuntimeStartupRequestSourceResult
import pro.liliya.android.runtime.AndroidProductRuntimeStartupProvisioningResult

sealed interface ProductionAndroidRuntimeStartupSourceInstallResult {
    data object Installed : ProductionAndroidRuntimeStartupSourceInstallResult
    data object AlreadyConfigured : ProductionAndroidRuntimeStartupSourceInstallResult
    data class SourceRejected(
        val reason: AndroidProductRuntimeStartupRequestSourceFailure
    ) : ProductionAndroidRuntimeStartupSourceInstallResult
    data class ProvisioningRejected(
        val result: AndroidProductRuntimeStartupProvisioningResult
    ) : ProductionAndroidRuntimeStartupSourceInstallResult
}

internal fun interface ProductionAndroidRuntimeStartupRequestSourcePort {
    fun create(): AndroidProductRuntimeStartupRequestSourceResult
}

internal fun interface ProductionAndroidLicenseServiceSecuritySyncPort {
    fun refresh(
        input: AndroidProductRuntimeStartupRequestSourceInput
    ): ProductionAndroidLicenseServiceSecuritySyncResult
}

internal fun interface ProductionAndroidOfflineResumeMetadataCommitPort {
    fun commit(
        input: AndroidProductRuntimeStartupRequestSourceInput,
        ready: AndroidProductRuntimeStartupProvisioningResult.Ready
    ): Boolean
}

/**
 * App entry from explicit startup-source inputs into the existing request/composition/install path.
 *
 * Startup Source Install != Provisioning Authority.
 * Startup Source Install != License/Capability Authority.
 * Startup Source Install != Retry/Recovery.
 */
object ProductionAndroidRuntimeStartupSourceInstall {
    fun prepareAndInstall(
        input: AndroidProductRuntimeStartupRequestSourceInput
    ): ProductionAndroidRuntimeStartupSourceInstallResult =
        prepareAndInstall(
            input = input,
            syncPort = ProductionAndroidLicenseServiceSecuritySyncPort { exact ->
                ProductionAndroidLicenseServiceSecuritySync.refresh(
                    context = exact.context.applicationContext,
                    input = exact
                )
            },
            metadataCommit = ProductionAndroidOfflineResumeMetadataCommitPort { exact, ready ->
                val metadata = ProductionAndroidOfflineResumeMetadata(
                    activeDek = ready.inputs.activeDek,
                    memoryStoreId = exact.preparedInputOwners.memoryStoreId,
                    knowledgeStoreId = exact.preparedInputOwners.knowledgeStoreId,
                    learningMutationStoreId = exact.preparedInputOwners.learningMutationStoreId,
                    cognitiveStorageDirectoryName = exact.cognitiveStorageDirectoryName,
                    semanticDirectoryName = exact.semanticDirectoryName
                )
                when (
                    ProductionAndroidOfflineResumeMetadataEncryptedStore
                        .create(exact.context.applicationContext)
                        .store(metadata)
                ) {
                    ProductionAndroidOfflineResumeMetadataStoreResult.Stored -> true
                    ProductionAndroidOfflineResumeMetadataStoreResult.Rejected,
                    ProductionAndroidOfflineResumeMetadataStoreResult.Failed -> false
                }
            }
        )

    internal fun prepareAndInstall(
        input: AndroidProductRuntimeStartupRequestSourceInput,
        syncPort: ProductionAndroidLicenseServiceSecuritySyncPort,
        metadataCommit: ProductionAndroidOfflineResumeMetadataCommitPort =
            ProductionAndroidOfflineResumeMetadataCommitPort { _, _ -> true }
    ): ProductionAndroidRuntimeStartupSourceInstallResult {
        val result = syncPort.refresh(input)
        val ready = result as? ProductionAndroidLicenseServiceSecuritySyncResult.Ready
        return prepareAndInstallAfterSecuritySync(
            syncAccepted = ready != null,
            sourcePort = ProductionAndroidRuntimeStartupRequestSourcePort {
                if (ready == null) {
                    AndroidProductRuntimeStartupRequestSourceResult.Rejected(
                        AndroidProductRuntimeStartupRequestSourceFailure.INTERNAL_FAILURE
                    )
                } else {
                    AndroidProductRuntimeStartupRequestSource.create(ready.input)
                }
            },
            readyCommit = ProductionAndroidRuntimeStartupProvisioningReadyCommitPort { provisioned ->
                ready != null && metadataCommit.commit(ready.input, provisioned)
            }
        )
    }

    internal fun prepareAndInstallAfterSecuritySync(
        syncAccepted: Boolean,
        sourcePort: ProductionAndroidRuntimeStartupRequestSourcePort,
        readyCommit: ProductionAndroidRuntimeStartupProvisioningReadyCommitPort =
            ProductionAndroidRuntimeStartupProvisioningReadyCommitPort { true }
    ): ProductionAndroidRuntimeStartupSourceInstallResult =
        if (syncAccepted) {
            prepareAndInstall(sourcePort, readyCommit)
        } else {
            ProductionAndroidRuntimeStartupSourceInstallResult.SourceRejected(
                AndroidProductRuntimeStartupRequestSourceFailure.INTERNAL_FAILURE
            )
        }

    internal fun prepareAndInstall(
        sourcePort: ProductionAndroidRuntimeStartupRequestSourcePort,
        readyCommit: ProductionAndroidRuntimeStartupProvisioningReadyCommitPort =
            ProductionAndroidRuntimeStartupProvisioningReadyCommitPort { true }
    ): ProductionAndroidRuntimeStartupSourceInstallResult {
        if (ProductionAndroidRuntimeConfiguration.current() != null) {
            return ProductionAndroidRuntimeStartupSourceInstallResult.AlreadyConfigured
        }

        val sourced = try {
            sourcePort.create()
        } catch (_: Exception) {
            return ProductionAndroidRuntimeStartupSourceInstallResult.SourceRejected(
                AndroidProductRuntimeStartupRequestSourceFailure.INTERNAL_FAILURE
            )
        }

        return when (sourced) {
            is AndroidProductRuntimeStartupRequestSourceResult.Ready ->
                when (
                    val installed = ProductionAndroidRuntimeStartupCompositionInstall.prepareAndInstall(
                        request = sourced.request,
                        readyCommit = readyCommit
                    )
                ) {
                    ProductionAndroidRuntimeStartupInstallResult.Installed ->
                        ProductionAndroidRuntimeStartupSourceInstallResult.Installed
                    ProductionAndroidRuntimeStartupInstallResult.AlreadyConfigured ->
                        ProductionAndroidRuntimeStartupSourceInstallResult.AlreadyConfigured
                    is ProductionAndroidRuntimeStartupInstallResult.Rejected ->
                        ProductionAndroidRuntimeStartupSourceInstallResult.ProvisioningRejected(
                            installed.provisioning
                        )
                }

            is AndroidProductRuntimeStartupRequestSourceResult.Rejected ->
                ProductionAndroidRuntimeStartupSourceInstallResult.SourceRejected(sourced.reason)
        }
    }
}
