package pro.liliya.app

import android.content.Context
import pro.liliya.android.runtime.AndroidProductRuntimeFirstRunProductInput

internal fun interface ProductionAndroidOfflineResumeProductInputFactory {
    fun create(
        material: ProductionAndroidOfflineResumeMaterial
    ): AndroidProductRuntimeFirstRunProductInput?
}

internal fun interface ProductionAndroidOfflineResumeMaterialPort {
    fun load(): ProductionAndroidOfflineResumeMaterialLoadResult
}

internal fun interface ProductionAndroidOfflineResumePolicyPort {
    fun current(): ProductionAndroidOfflineResumeProductInputFactory?
}

internal fun interface ProductionAndroidOfflineResumeInstallPort {
    fun install(
        input: AndroidProductRuntimeFirstRunProductInput
    ): ProductionAndroidFirstRunProductInstallResult
}

/**
 * Process-local product policy required to reconstruct a cold-start runtime input.
 *
 * Policy Factory != durable state.
 * Policy Factory != License.
 * Policy Factory != Authority ownership.
 * Policy Factory != Execution permission.
 *
 * Durable stores provide exact persisted material only. A new process must explicitly install
 * this factory before offline reconstruction can create fresh first-run inputs and re-enter the
 * canonical trust/Authority/startup pipeline.
 */
internal object ProductionAndroidOfflineResumePolicyOwner {
    @Volatile
    private var factory: ProductionAndroidOfflineResumeProductInputFactory? = null

    @Synchronized
    fun install(value: ProductionAndroidOfflineResumeProductInputFactory): Boolean {
        if (factory != null) return false
        factory = value
        return true
    }

    fun current(): ProductionAndroidOfflineResumeProductInputFactory? = factory

    @Synchronized
    internal fun clearForTests() {
        factory = null
    }
}

internal enum class ProductionAndroidOfflineResumeBootstrapFailure {
    MATERIAL_REJECTED,
    POLICY_REQUIRED,
    INPUT_REJECTED,
    INSTALL_REJECTED,
    FAILED
}

internal sealed interface ProductionAndroidOfflineResumeBootstrapResult {
    data class Ready(
        val install: ProductionAndroidFirstRunProductInstallResult
    ) : ProductionAndroidOfflineResumeBootstrapResult

    data class Rejected(
        val reason: ProductionAndroidOfflineResumeBootstrapFailure,
        val materialFailure: ProductionAndroidOfflineResumeMaterialFailure? = null
    ) : ProductionAndroidOfflineResumeBootstrapResult
}

/**
 * Cold-start reconstruction boundary.
 *
 * Offline Resume Bootstrap != Authority restoration.
 * Offline Resume Bootstrap != chat replay.
 * Offline Resume Bootstrap != bypass of License verification.
 *
 * Every reconstructed input is sent through ProductionAndroidFirstRunProductInstall, which
 * re-runs trust verification and fresh Authority admission before startup ownership can exist.
 */
internal object ProductionAndroidOfflineResumeBootstrap {
    fun prepareAndInstall(
        context: Context
    ): ProductionAndroidOfflineResumeBootstrapResult =
        prepareAndInstall(
            materialPort = ProductionAndroidOfflineResumeMaterialPort {
                ProductionAndroidOfflineResumeMaterialLoader.load(
                    context.applicationContext
                )
            },
            policyPort = ProductionAndroidOfflineResumePolicyPort {
                ProductionAndroidOfflineResumePolicyOwner.current()
            },
            installPort = ProductionAndroidOfflineResumeInstallPort { input ->
                ProductionAndroidFirstRunProductInstall.prepareAndInstall(input)
            }
        )

    internal fun prepareAndInstall(
        materialPort: ProductionAndroidOfflineResumeMaterialPort,
        policyPort: ProductionAndroidOfflineResumePolicyPort,
        installPort: ProductionAndroidOfflineResumeInstallPort
    ): ProductionAndroidOfflineResumeBootstrapResult {
        val material = when (
            val loaded = try {
                materialPort.load()
            } catch (_: Throwable) {
                return rejected(ProductionAndroidOfflineResumeBootstrapFailure.FAILED)
            }
        ) {
            is ProductionAndroidOfflineResumeMaterialLoadResult.Ready -> loaded.material
            is ProductionAndroidOfflineResumeMaterialLoadResult.Rejected ->
                return ProductionAndroidOfflineResumeBootstrapResult.Rejected(
                    reason = ProductionAndroidOfflineResumeBootstrapFailure.MATERIAL_REJECTED,
                    materialFailure = loaded.reason
                )
        }

        val factory = try {
            policyPort.current()
        } catch (_: Throwable) {
            return rejected(ProductionAndroidOfflineResumeBootstrapFailure.FAILED)
        } ?: return rejected(ProductionAndroidOfflineResumeBootstrapFailure.POLICY_REQUIRED)

        val input = try {
            factory.create(material)
        } catch (_: Throwable) {
            return rejected(ProductionAndroidOfflineResumeBootstrapFailure.FAILED)
        } ?: return rejected(ProductionAndroidOfflineResumeBootstrapFailure.INPUT_REJECTED)

        return when (
            val installed = try {
                installPort.install(input)
            } catch (_: Throwable) {
                return rejected(ProductionAndroidOfflineResumeBootstrapFailure.FAILED)
            }
        ) {
            is ProductionAndroidFirstRunProductInstallResult.Installed,
            ProductionAndroidFirstRunProductInstallResult.AlreadyConfigured ->
                ProductionAndroidOfflineResumeBootstrapResult.Ready(installed)

            is ProductionAndroidFirstRunProductInstallResult.ProductInputRejected,
            ProductionAndroidFirstRunProductInstallResult.TrustVerificationRejected,
            is ProductionAndroidFirstRunProductInstallResult.AuthorityRejected,
            ProductionAndroidFirstRunProductInstallResult.DurableLicenseRejected,
            ProductionAndroidFirstRunProductInstallResult.Failed ->
                rejected(ProductionAndroidOfflineResumeBootstrapFailure.INSTALL_REJECTED)
        }
    }

    private fun rejected(
        reason: ProductionAndroidOfflineResumeBootstrapFailure
    ) = ProductionAndroidOfflineResumeBootstrapResult.Rejected(reason)
}
