package pro.liliya.app

import pro.liliya.android.runtime.AndroidProductRuntimeAdmissionResult
import pro.liliya.android.runtime.AndroidProductRuntimeHostBootstrap
import pro.liliya.android.runtime.AndroidProductRuntimeHostBootstrapFailure
import pro.liliya.android.runtime.AndroidProductRuntimeHostBootstrapResult
import pro.liliya.android.runtime.AndroidProductRuntimeHostPreparedInputs

/**
 * Explicit production composition seam between the Android app host and already-authoritative
 * Product Runtime owners.
 *
 * Runtime Wiring != Provisioning Authority.
 * Runtime Wiring != License Authority.
 * Runtime Wiring != Capability Authority.
 * Runtime Wiring != Runtime State Authority.
 */
data class ProductionAndroidRuntimeWiringSources(
    val admission: () -> AndroidProductRuntimeAdmissionResult,
    val preparedInputs: () -> AndroidProductRuntimeHostPreparedInputs
)

internal fun interface ProductionAndroidRuntimeBootstrapPort {
    fun start(
        inputs: AndroidProductRuntimeHostPreparedInputs,
        admission: AndroidProductRuntimeAdmissionResult.Admitted
    ): AndroidProductRuntimeHostBootstrapResult
}

object ProductionAndroidRuntimeWiring {
    fun install(sources: ProductionAndroidRuntimeWiringSources): Boolean =
        ProductionAndroidAppTrustedWiring.install(createStartPort(sources))

    internal fun createStartPort(
        sources: ProductionAndroidRuntimeWiringSources,
        bootstrap: ProductionAndroidRuntimeBootstrapPort =
            ProductionAndroidRuntimeBootstrapPort(AndroidProductRuntimeHostBootstrap::start)
    ): ProductionAndroidAppRuntimeStartPort =
        ProductionAndroidAppRuntimeStartPort.fromProductRuntimeBootstrap {
            val admission = try {
                sources.admission()
            } catch (_: Exception) {
                return@fromProductRuntimeBootstrap AndroidProductRuntimeHostBootstrapResult.Rejected(
                    AndroidProductRuntimeHostBootstrapFailure.INTERNAL_FAILURE
                )
            }

            if (admission !is AndroidProductRuntimeAdmissionResult.Admitted) {
                return@fromProductRuntimeBootstrap AndroidProductRuntimeHostBootstrapResult.Rejected(
                    AndroidProductRuntimeHostBootstrapFailure.CREATE_REJECTED
                )
            }

            val inputs = try {
                sources.preparedInputs()
            } catch (_: Exception) {
                return@fromProductRuntimeBootstrap AndroidProductRuntimeHostBootstrapResult.Rejected(
                    AndroidProductRuntimeHostBootstrapFailure.INTERNAL_FAILURE
                )
            }

            try {
                bootstrap.start(inputs, admission)
            } catch (_: Exception) {
                AndroidProductRuntimeHostBootstrapResult.Rejected(
                    AndroidProductRuntimeHostBootstrapFailure.INTERNAL_FAILURE
                )
            }
        }
}
