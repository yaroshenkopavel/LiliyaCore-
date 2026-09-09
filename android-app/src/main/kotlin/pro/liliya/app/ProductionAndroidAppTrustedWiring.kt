package pro.liliya.app

/**
 * Explicit trusted bootstrap seam for the outer Android product host.
 *
 * The app host itself does not create License, Authority, DEK, model selection or governance.
 */
object ProductionAndroidAppTrustedWiring {
    @Volatile
    private var installed: ProductionAndroidAppRuntimeStartPort? = null

    @Synchronized
    fun install(port: ProductionAndroidAppRuntimeStartPort): Boolean {
        if (installed != null) return false
        installed = port
        return true
    }

    internal fun current(): ProductionAndroidAppRuntimeStartPort? = installed

    @Synchronized
    internal fun clearForTests() {
        installed = null
    }
}
