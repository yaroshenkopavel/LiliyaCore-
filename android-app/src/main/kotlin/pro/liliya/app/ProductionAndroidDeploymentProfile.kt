package pro.liliya.app

import android.content.Context
import java.net.URL
import pro.liliya.core.licensetransport.LicenseHttpTransportConfig
import pro.liliya.core.licensetransport.LicenseServiceTransportRequest

/** Deployment binding for an explicitly supplied product policy and runtime template. */
internal object ProductionAndroidDeploymentProfile {
    fun source(
        context: Context,
        legacyProductAuthRequest: LicenseServiceTransportRequest,
        productTemplate: ProductionAndroidFirstRunProductInputTemplate
    ): ProductionAndroidFirstRunProductProfileSource = ProductionAndroidFirstRunProductProfileSource {
        val appContext = context.applicationContext
        require(productTemplate.context.applicationContext === appContext)
        val ca = ProductionAndroidDeploymentTrustAssets.licensingCa(appContext)
        val trustKey = ProductionAndroidDeploymentTrustAssets.licenseTrustKeyV1(appContext)
        ProductionAndroidFirstRunProductProfile(
            transport = LicenseHttpTransportConfig(
                endpoint = URL("https://liliya-licensing.internal:8443/v1/license"),
                connectTimeoutMillis = 3_000,
                readTimeoutMillis = 5_000
            ),
            licenseRequest = legacyProductAuthRequest,
            productInputTemplate = productTemplate.copy(licenseTrustKeys = listOf(trustKey)),
            tlsTrustAnchorDer = ca
        )
    }
}
