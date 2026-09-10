package pro.liliya.app

import pro.liliya.android.runtime.AndroidProductRuntimeFirstRunProductInput
import pro.liliya.android.runtime.AndroidProductRuntimeFirstRunProductInputFailure
import pro.liliya.android.runtime.AndroidProductRuntimeFirstRunProductInputFactory
import pro.liliya.android.runtime.AndroidProductRuntimeFirstRunProductInputResult
import pro.liliya.android.runtime.AndroidProductRuntimeStartupAuthorityAssemblyFailure
import pro.liliya.android.runtime.AndroidProductRuntimeStartupInputOwnership

sealed interface ProductionAndroidFirstRunProductInstallResult {
    data class Installed(
        val ownership: AndroidProductRuntimeStartupInputOwnership
    ) : ProductionAndroidFirstRunProductInstallResult

    data class ProductInputRejected(
        val reason: AndroidProductRuntimeFirstRunProductInputFailure
    ) : ProductionAndroidFirstRunProductInstallResult

    data object AlreadyConfigured : ProductionAndroidFirstRunProductInstallResult
    data object TrustVerificationRejected : ProductionAndroidFirstRunProductInstallResult

    data class AuthorityRejected(
        val reason: AndroidProductRuntimeStartupAuthorityAssemblyFailure
    ) : ProductionAndroidFirstRunProductInstallResult

    data object Failed : ProductionAndroidFirstRunProductInstallResult
}

internal fun interface ProductionAndroidFirstRunProductResolvePort {
    fun resolve(): AndroidProductRuntimeFirstRunProductInputResult
}

internal fun interface ProductionAndroidFirstRunStartupInstallPort {
    fun install(
        input: pro.liliya.android.runtime.AndroidProductRuntimeStartupInputAssemblyInput
    ): ProductionAndroidRuntimeStartupInputAssemblyInstallResult
}

/**
 * Android application boundary that connects explicit product first-run inputs to the existing
 * install-once startup configuration.
 *
 * First-Run App Install != Product Policy.
 * First-Run App Install != License Issuance.
 * First-Run App Install != Authority Minting.
 * First-Run App Install != Model/DEK Selection.
 */
object ProductionAndroidFirstRunProductInstall {
    fun prepareAndInstall(
        input: AndroidProductRuntimeFirstRunProductInput
    ): ProductionAndroidFirstRunProductInstallResult =
        prepareAndInstall(
            resolvePort = ProductionAndroidFirstRunProductResolvePort {
                AndroidProductRuntimeFirstRunProductInputFactory.create(input)
            },
            installPort = ProductionAndroidFirstRunStartupInstallPort {
                ProductionAndroidRuntimeStartupInputAssemblyInstall.prepareAndInstall(it)
            }
        )

    internal fun prepareAndInstall(
        resolvePort: ProductionAndroidFirstRunProductResolvePort,
        installPort: ProductionAndroidFirstRunStartupInstallPort
    ): ProductionAndroidFirstRunProductInstallResult {
        val resolved = try {
            resolvePort.resolve()
        } catch (_: Exception) {
            return ProductionAndroidFirstRunProductInstallResult.Failed
        }

        return when (resolved) {
            is AndroidProductRuntimeFirstRunProductInputResult.Rejected ->
                ProductionAndroidFirstRunProductInstallResult.ProductInputRejected(
                    resolved.reason
                )

            is AndroidProductRuntimeFirstRunProductInputResult.Ready -> {
                val installed = try {
                    installPort.install(resolved.input)
                } catch (_: Exception) {
                    return ProductionAndroidFirstRunProductInstallResult.Failed
                }
                mapInstallResult(installed)
            }
        }
    }

    internal fun mapInstallResult(
        result: ProductionAndroidRuntimeStartupInputAssemblyInstallResult
    ): ProductionAndroidFirstRunProductInstallResult =
        when (result) {
            is ProductionAndroidRuntimeStartupInputAssemblyInstallResult.Installed ->
                ProductionAndroidFirstRunProductInstallResult.Installed(
                    result.ownership
                )
            ProductionAndroidRuntimeStartupInputAssemblyInstallResult.AlreadyConfigured ->
                ProductionAndroidFirstRunProductInstallResult.AlreadyConfigured
            ProductionAndroidRuntimeStartupInputAssemblyInstallResult.TrustVerificationRejected ->
                ProductionAndroidFirstRunProductInstallResult.TrustVerificationRejected
            is ProductionAndroidRuntimeStartupInputAssemblyInstallResult.AuthorityRejected ->
                ProductionAndroidFirstRunProductInstallResult.AuthorityRejected(
                    result.reason
                )
            ProductionAndroidRuntimeStartupInputAssemblyInstallResult.Failed ->
                ProductionAndroidFirstRunProductInstallResult.Failed
        }
}
