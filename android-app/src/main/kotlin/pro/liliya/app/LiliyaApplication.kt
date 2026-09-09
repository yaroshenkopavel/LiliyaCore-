package pro.liliya.app

import android.app.Application
import pro.liliya.android.runtime.AndroidProductRuntimeStartupCompositionRequest
import pro.liliya.android.runtime.AndroidProductRuntimeStartupProvisioningPorts
import pro.liliya.android.runtime.AndroidProductRuntimeStartupRequestSourceInput

class LiliyaApplication : Application() {
    val runtimeOwner: ProductionAndroidAppRuntimeOwner = ProductionAndroidAppRuntimeOwner()
    private val startupCoordinator = ProductionAndroidRuntimeStartupCoordinator()

    fun configureRuntime(sources: ProductionAndroidRuntimeWiringSources): Boolean =
        ProductionAndroidRuntimeConfiguration.install(sources)

    fun provisionRuntime(
        ports: AndroidProductRuntimeStartupProvisioningPorts
    ): ProductionAndroidRuntimeStartupInstallResult =
        ProductionAndroidRuntimeStartupInstall.prepareAndInstall(ports)

    fun provisionRuntime(
        request: AndroidProductRuntimeStartupCompositionRequest
    ): ProductionAndroidRuntimeStartupInstallResult =
        ProductionAndroidRuntimeStartupCompositionInstall.prepareAndInstall(request)

    fun provisionRuntime(
        input: AndroidProductRuntimeStartupRequestSourceInput
    ): ProductionAndroidRuntimeStartupSourceInstallResult =
        ProductionAndroidRuntimeStartupSourceInstall.prepareAndInstall(input)

    fun configureStartup(
        input: AndroidProductRuntimeStartupRequestSourceInput
    ): Boolean = ProductionAndroidRuntimeStartupInputConfiguration.install(input)

    fun startApplicationRuntime(): ProductionAndroidAppStartupOutcome {
        val input = ProductionAndroidRuntimeStartupInputConfiguration.current()
        return startupCoordinator.start(
            hasStartupInput = input != null,
            configuredPort = ProductionAndroidRuntimeConfiguredPort {
                ProductionAndroidRuntimeConfiguration.current() != null ||
                    ProductionAndroidAppTrustedWiring.current() != null
            },
            provisionPort = ProductionAndroidRuntimeStartupProvisionPort {
                val exact = input
                if (exact == null) {
                    ProductionAndroidRuntimeStartupSourceInstallResult.SourceRejected(
                        pro.liliya.android.runtime.AndroidProductRuntimeStartupRequestSourceFailure.INTERNAL_FAILURE
                    )
                } else {
                    ProductionAndroidRuntimeStartupSourceInstall.prepareAndInstall(exact)
                }
            },
            runtimeStartPort = ProductionAndroidRuntimeStartStatePort { startRuntime() }
        )
    }

    fun startRuntime(): ProductionAndroidAppRuntimeState {
        ProductionAndroidRuntimeConfigurationInstaller.ensureTrustedWiringInstalled()
        return runtimeOwner.start(ProductionAndroidAppTrustedWiring.current())
    }

    override fun onTerminate() {
        runtimeOwner.close()
        super.onTerminate()
    }
}
