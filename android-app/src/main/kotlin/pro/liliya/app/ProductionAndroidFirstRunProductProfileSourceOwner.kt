package pro.liliya.app

/**
 * Process-local installation point for the caller-approved deployment profile source.
 *
 * Source Owner != Product Profile Discovery.
 * Source Owner != Secret Storage.
 * Source Owner != License Trust or Authority Discovery.
 *
 * The application never invents deployment values. A product/deployment integration must install
 * exactly one explicit source before the provisioning launcher may configure first-run acquisition.
 */
internal object ProductionAndroidFirstRunProductProfileSourceOwner {
    @Volatile
    private var source: ProductionAndroidFirstRunProductProfileSource? = null

    @Synchronized
    fun install(value: ProductionAndroidFirstRunProductProfileSource): Boolean {
        if (source != null) return false
        source = value
        return true
    }

    fun current(): ProductionAndroidFirstRunProductProfileSource? = source

    @Synchronized
    internal fun clearForTests() {
        source = null
    }
}

internal sealed interface ProductionAndroidFirstRunDeploymentBootstrapResult {
    data object ProductProfileRequired : ProductionAndroidFirstRunDeploymentBootstrapResult
    data object Installed : ProductionAndroidFirstRunDeploymentBootstrapResult
    data object AlreadyConfigured : ProductionAndroidFirstRunDeploymentBootstrapResult
    data object Failed : ProductionAndroidFirstRunDeploymentBootstrapResult
}

/**
 * Installs the authenticated first-run configuration only from an explicitly installed deployment
 * source. Missing source is fail-closed; no fallback/default profile is manufactured.
 */
internal fun LiliyaApplication.configureInstalledAuthenticatedFirstRunProduct():
    ProductionAndroidFirstRunDeploymentBootstrapResult {
    val source = ProductionAndroidFirstRunProductProfileSourceOwner.current()
        ?: return ProductionAndroidFirstRunDeploymentBootstrapResult.ProductProfileRequired
    return when (configureAuthenticatedFirstRunProduct(source)) {
        ProductionAndroidFirstRunAuthenticatedProductConfigurationInstallResult.Installed ->
            ProductionAndroidFirstRunDeploymentBootstrapResult.Installed
        ProductionAndroidFirstRunAuthenticatedProductConfigurationInstallResult.AlreadyConfigured ->
            ProductionAndroidFirstRunDeploymentBootstrapResult.AlreadyConfigured
        ProductionAndroidFirstRunAuthenticatedProductConfigurationInstallResult.Failed ->
            ProductionAndroidFirstRunDeploymentBootstrapResult.Failed
    }
}
