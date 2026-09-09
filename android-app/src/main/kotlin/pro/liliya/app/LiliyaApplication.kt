package pro.liliya.app

import android.app.Application

class LiliyaApplication : Application() {
    val runtimeOwner: ProductionAndroidAppRuntimeOwner = ProductionAndroidAppRuntimeOwner()

    fun startRuntime(): ProductionAndroidAppRuntimeState =
        runtimeOwner.start(ProductionAndroidAppTrustedWiring.current())

    override fun onTerminate() {
        runtimeOwner.close()
        super.onTerminate()
    }
}
