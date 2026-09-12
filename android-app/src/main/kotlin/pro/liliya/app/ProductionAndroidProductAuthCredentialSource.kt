package pro.liliya.app

import pro.liliya.core.licensetransport.LicenseHttpBearerCredential

/**
 * Supplies one plaintext copy of the product request-authentication secret on demand.
 *
 * Product Auth Secret != License Entitlement.
 * Product Auth Secret != License Verification Trust.
 * Product Auth Secret != Capability Authority.
 *
 * Implementations own secret provisioning/storage policy. The returned byte array is transfer
 * ownership for one factory call only and is always zeroized by this adapter.
 */
internal fun interface ProductionAndroidProductAuthCredentialSource {
    fun openSecret(): ByteArray
}

/**
 * Adapts a product-auth secret source to the accepted per-attempt licensing bearer boundary.
 *
 * No secret is cached here. Every acquisition attempt re-opens the source, creates one
 * caller-owned [LicenseHttpBearerCredential], and zeroizes the temporary plaintext buffer.
 */
internal object ProductionAndroidProductAuthCredentialAdapter {
    fun bearerFactory(
        source: ProductionAndroidProductAuthCredentialSource
    ): ProductionAndroidLicenseBearerCredentialFactory =
        ProductionAndroidLicenseBearerCredentialFactory {
            val plaintext = source.openSecret()
            try {
                LicenseHttpBearerCredential.of(plaintext)
            } finally {
                plaintext.fill(0)
            }
        }
}
