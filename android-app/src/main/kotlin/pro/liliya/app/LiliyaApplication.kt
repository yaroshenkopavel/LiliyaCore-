package pro.liliya.app

import android.app.Application
import pro.liliya.android.runtime.AndroidProductRuntimeStartupCompositionRequest
import pro.liliya.android.runtime.AndroidProductRuntimeStartupProvisioningPorts
import pro.liliya.android.runtime.AndroidProductRuntimeStartupRequestSourceInput

class LiliyaApplication : Application() {
    val runtimeOwner: ProductionAndroidAppRuntimeOwner = ProductionAndroidAppRuntimeOwner()

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

    fun startRuntime(): ProductionAndroidAppRuntimeState {
        ProductionAndroidRuntimeConfigurationInstaller.ensureTrustedWiringInstalled()
        return runtimeOwner.start(ProductionAndroidAppTrustedWiring.current())
    }

    override fun onTerminate() {
        runtimeOwner.close()
        super.onTerminate()
    }
}
