package pro.liliya.app

import pro.liliya.android.runtime.AndroidProductRuntimeStartupAuthorityAssemblyFailure
import pro.liliya.android.runtime.AndroidProductRuntimeStartupInputAssembly
import pro.liliya.android.runtime.AndroidProductRuntimeStartupInputAssemblyInput
import pro.liliya.android.runtime.AndroidProductRuntimeStartupInputAssemblyResult
import pro.liliya.android.runtime.AndroidProductRuntimeStartupInputOwnership

sealed interface ProductionAndroidRuntimeStartupInputAssemblyInstallResult {
    data class Installed(
        val ownership: AndroidProductRuntimeStartupInputOwnership
    ) : ProductionAndroidRuntimeStartupInputAssemblyInstallResult

    data object AlreadyConfigured : ProductionAndroidRuntimeStartupInputAssemblyInstallResult

    data object TrustVerificationRejected : ProductionAndroidRuntimeStartupInputAssemblyInstallResult

    data class AuthorityRejected(
        val reason: AndroidProductRuntimeStartupAuthorityAssemblyFailure
    ) : ProductionAndroidRuntimeStartupInputAssemblyInstallResult

    data object Failed : ProductionAndroidRuntimeStartupInputAssemblyInstallResult
}

internal fun interface ProductionAndroidRuntimeStartupInputAssemblyPort {
    fun assemble(): AndroidProductRuntimeStartupInputAssemblyResult
}

/**
 * Atomically connects startup input assembly to the install-once app startup configuration.
 *
 * Assembly Install != Trust Authority.
 * Assembly Install != Capability Grant Authority.
 * Assembly Install != Retry/Recovery.
 */
object ProductionAndroidRuntimeStartupInputAssemblyInstall {
    @Volatile
    private var installedOwnership: AndroidProductRuntimeStartupInputOwnership? = null

    fun prepareAndInstall(
        input: AndroidProductRuntimeStartupInputAssemblyInput
    ): ProductionAndroidRuntimeStartupInputAssemblyInstallResult =
        prepareAndInstall(
            ProductionAndroidRuntimeStartupInputAssemblyPort {
                AndroidProductRuntimeStartupInputAssembly.create(input)
            }
        )

    @Synchronized
    internal fun prepareAndInstall(
        assemblyPort: ProductionAndroidRuntimeStartupInputAssemblyPort
    ): ProductionAndroidRuntimeStartupInputAssemblyInstallResult {
        installedOwnership?.let {
            return ProductionAndroidRuntimeStartupInputAssemblyInstallResult.AlreadyConfigured
        }
        if (ProductionAndroidRuntimeStartupInputConfiguration.current() != null) {
            return ProductionAndroidRuntimeStartupInputAssemblyInstallResult.AlreadyConfigured
        }

        val assembled = try {
            assemblyPort.assemble()
        } catch (_: Exception) {
            return ProductionAndroidRuntimeStartupInputAssemblyInstallResult.Failed
        }

        return when (assembled) {
            is AndroidProductRuntimeStartupInputAssemblyResult.Ready -> {
                val ownership = assembled.ownership
                if (ProductionAndroidRuntimeStartupInputConfiguration.install(ownership.sourceInput)) {
                    installedOwnership = ownership
                    ProductionAndroidRuntimeStartupInputAssemblyInstallResult.Installed(ownership)
                } else {
                    ownership.releaseAuthorityOwnership()
                    ProductionAndroidRuntimeStartupInputAssemblyInstallResult.AlreadyConfigured
                }
            }
            is AndroidProductRuntimeStartupInputAssemblyResult.TrustVerificationRejected ->
                ProductionAndroidRuntimeStartupInputAssemblyInstallResult.TrustVerificationRejected
            is AndroidProductRuntimeStartupInputAssemblyResult.AuthorityRejected ->
                ProductionAndroidRuntimeStartupInputAssemblyInstallResult.AuthorityRejected(
                    assembled.reason
                )
            AndroidProductRuntimeStartupInputAssemblyResult.Failed ->
                ProductionAndroidRuntimeStartupInputAssemblyInstallResult.Failed
        }
    }

    @Synchronized
    internal fun clearForTests() {
        installedOwnership?.releaseAuthorityOwnership()
        installedOwnership = null
    }
}
