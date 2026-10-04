package pro.liliya.app

import android.content.Context
import java.io.File
import pro.liliya.core.license.LicenseSignedEnvelope

internal data class ProductionAndroidOfflineResumeMaterial(
    val licenseEnvelope: LicenseSignedEnvelope,
    val deploymentProfile: ProductionAndroidOfflineDeploymentProfile,
    val resumeMetadata: ProductionAndroidOfflineResumeMetadata,
    val localModelFile: File
)

internal enum class ProductionAndroidOfflineResumeMaterialFailure {
    LICENSE_MISSING,
    LICENSE_REJECTED,
    DEPLOYMENT_PROFILE_MISSING,
    DEPLOYMENT_PROFILE_REJECTED,
    RESUME_METADATA_MISSING,
    RESUME_METADATA_REJECTED,
    LOCAL_MODEL_MISSING
}

internal sealed interface ProductionAndroidOfflineResumeMaterialLoadResult {
    data class Ready(
        val material: ProductionAndroidOfflineResumeMaterial
    ) : ProductionAndroidOfflineResumeMaterialLoadResult

    data class Rejected(
        val reason: ProductionAndroidOfflineResumeMaterialFailure
    ) : ProductionAndroidOfflineResumeMaterialLoadResult
}

internal fun interface ProductionAndroidOfflineResumeLicenseLoadPort {
    fun load(): ProductionAndroidActivatedLicenseLoadResult
}

internal fun interface ProductionAndroidOfflineResumeDeploymentLoadPort {
    fun load(): ProductionAndroidOfflineDeploymentProfileLoadResult
}

internal fun interface ProductionAndroidOfflineResumeMetadataLoadPort {
    fun load(): ProductionAndroidOfflineResumeMetadataLoadResult
}

internal fun interface ProductionAndroidOfflineResumeLocalModelPort {
    fun current(): File?
}

/**
 * Loads only durable material needed to reconstruct a cold-start request.
 *
 * Material Load != License verification.
 * Material Load != Authority.
 * Material Load != Execution.
 * Material Load != chat replay.
 */
internal object ProductionAndroidOfflineResumeMaterialLoader {
    fun load(
        context: Context
    ): ProductionAndroidOfflineResumeMaterialLoadResult =
        load(
            licensePort = ProductionAndroidOfflineResumeLicenseLoadPort {
                ProductionAndroidActivatedLicenseEncryptedStore.create(context).load()
            },
            deploymentPort = ProductionAndroidOfflineResumeDeploymentLoadPort {
                ProductionAndroidOfflineDeploymentProfileEncryptedStore.create(context).load()
            },
            metadataPort = ProductionAndroidOfflineResumeMetadataLoadPort {
                ProductionAndroidOfflineResumeMetadataEncryptedStore.create(context).load()
            },
            localModelPort = ProductionAndroidOfflineResumeLocalModelPort {
                ProductionAndroidLocalModelSelection.current()
            }
        )

    internal fun load(
        licensePort: ProductionAndroidOfflineResumeLicenseLoadPort,
        deploymentPort: ProductionAndroidOfflineResumeDeploymentLoadPort,
        metadataPort: ProductionAndroidOfflineResumeMetadataLoadPort,
        localModelPort: ProductionAndroidOfflineResumeLocalModelPort
    ): ProductionAndroidOfflineResumeMaterialLoadResult {
        val envelope = when (val loaded = try { licensePort.load() } catch (_: Throwable) {
            ProductionAndroidActivatedLicenseLoadResult.Rejected
        }) {
            ProductionAndroidActivatedLicenseLoadResult.Missing ->
                return rejected(ProductionAndroidOfflineResumeMaterialFailure.LICENSE_MISSING)
            ProductionAndroidActivatedLicenseLoadResult.Rejected ->
                return rejected(ProductionAndroidOfflineResumeMaterialFailure.LICENSE_REJECTED)
            is ProductionAndroidActivatedLicenseLoadResult.Loaded -> loaded.envelope
        }

        val deployment = when (val loaded = try { deploymentPort.load() } catch (_: Throwable) {
            ProductionAndroidOfflineDeploymentProfileLoadResult.Rejected
        }) {
            ProductionAndroidOfflineDeploymentProfileLoadResult.Missing ->
                return rejected(
                    ProductionAndroidOfflineResumeMaterialFailure.DEPLOYMENT_PROFILE_MISSING
                )
            ProductionAndroidOfflineDeploymentProfileLoadResult.Rejected ->
                return rejected(
                    ProductionAndroidOfflineResumeMaterialFailure.DEPLOYMENT_PROFILE_REJECTED
                )
            is ProductionAndroidOfflineDeploymentProfileLoadResult.Loaded -> loaded.profile
        }

        val metadata = when (val loaded = try { metadataPort.load() } catch (_: Throwable) {
            ProductionAndroidOfflineResumeMetadataLoadResult.Rejected
        }) {
            ProductionAndroidOfflineResumeMetadataLoadResult.Missing ->
                return rejected(
                    ProductionAndroidOfflineResumeMaterialFailure.RESUME_METADATA_MISSING
                )
            ProductionAndroidOfflineResumeMetadataLoadResult.Rejected ->
                return rejected(
                    ProductionAndroidOfflineResumeMaterialFailure.RESUME_METADATA_REJECTED
                )
            is ProductionAndroidOfflineResumeMetadataLoadResult.Loaded -> loaded.metadata
        }

        val model = try { localModelPort.current() } catch (_: Throwable) { null }
        if (model == null || !model.isFile) {
            return rejected(ProductionAndroidOfflineResumeMaterialFailure.LOCAL_MODEL_MISSING)
        }

        return ProductionAndroidOfflineResumeMaterialLoadResult.Ready(
            ProductionAndroidOfflineResumeMaterial(
                licenseEnvelope = envelope,
                deploymentProfile = deployment,
                resumeMetadata = metadata,
                localModelFile = model
            )
        )
    }

    private fun rejected(
        reason: ProductionAndroidOfflineResumeMaterialFailure
    ) = ProductionAndroidOfflineResumeMaterialLoadResult.Rejected(reason)
}
