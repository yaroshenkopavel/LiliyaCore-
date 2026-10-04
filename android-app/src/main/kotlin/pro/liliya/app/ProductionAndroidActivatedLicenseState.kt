package pro.liliya.app

import android.content.Context

internal enum class ProductionAndroidActivatedLicenseState {
    MISSING,
    AVAILABLE,
    REJECTED
}

/**
 * Bounded cold-resume classifier for the durable activated License artifact.
 *
 * AVAILABLE does not mean verified, admitted or executable. It only means an authenticated
 * encrypted envelope is present and can be submitted again to the normal trust/Authority path.
 */
internal object ProductionAndroidActivatedLicenseStateResolver {
    fun resolve(context: Context): ProductionAndroidActivatedLicenseState =
        resolve {
            ProductionAndroidActivatedLicenseEncryptedStore.create(context).load()
        }

    internal fun resolve(
        load: () -> ProductionAndroidActivatedLicenseLoadResult
    ): ProductionAndroidActivatedLicenseState =
        when (load()) {
            ProductionAndroidActivatedLicenseLoadResult.Missing ->
                ProductionAndroidActivatedLicenseState.MISSING
            is ProductionAndroidActivatedLicenseLoadResult.Loaded ->
                ProductionAndroidActivatedLicenseState.AVAILABLE
            ProductionAndroidActivatedLicenseLoadResult.Rejected ->
                ProductionAndroidActivatedLicenseState.REJECTED
        }
}
