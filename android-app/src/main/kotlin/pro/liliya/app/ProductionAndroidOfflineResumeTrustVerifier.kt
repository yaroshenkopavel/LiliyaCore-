package pro.liliya.app

import android.content.Context
import pro.liliya.android.runtime.AndroidProductRuntimeLicenseTrustKey
import pro.liliya.android.runtime.AndroidProductRuntimeStartupTrustAssembly
import pro.liliya.android.runtime.AndroidProductRuntimeStartupTrustAssemblyResult
import pro.liliya.android.runtime.AndroidProductRuntimeStartupTrustInput
import pro.liliya.android.runtime.AndroidProductRuntimeStartupTrustInputFactory
import pro.liliya.android.runtime.AndroidProductRuntimeStartupTrustInputFactoryResult
import pro.liliya.android.runtime.AndroidProductRuntimeStartupTrustOwnership

internal enum class ProductionAndroidOfflineResumeTrustFailure {
    TRUST_INPUT_REJECTED,
    VERIFICATION_REJECTED,
    FAILED
}

internal sealed interface ProductionAndroidOfflineResumeTrustResult {
    data class Ready(
        val ownership: AndroidProductRuntimeStartupTrustOwnership
    ) : ProductionAndroidOfflineResumeTrustResult

    data class Rejected(
        val reason: ProductionAndroidOfflineResumeTrustFailure
    ) : ProductionAndroidOfflineResumeTrustResult
}

internal fun interface ProductionAndroidOfflineResumeTrustAssemblyPort {
    fun create(
        input: AndroidProductRuntimeStartupTrustInput
    ): AndroidProductRuntimeStartupTrustAssemblyResult
}

/**
 * Re-verifies the durable signed License before any cold-resume admission or Authority plan is
 * allowed to exist.
 *
 * Durable License != Verified License.
 * Trust Verification != Authority.
 * Trust Verification != Execution.
 */
internal object ProductionAndroidOfflineResumeTrustVerifier {
    fun verify(
        context: Context,
        material: ProductionAndroidOfflineResumeMaterial
    ): ProductionAndroidOfflineResumeTrustResult {
        val observability = try {
            ProductionAndroidAppObservability.create(context.applicationContext).also {
                it.installLoggerWriter()
            }
        } catch (_: Throwable) {
            return rejected(ProductionAndroidOfflineResumeTrustFailure.FAILED)
        }

        val keys = try {
            material.deploymentProfile.copyLicenseTrustKeys().map { key ->
                AndroidProductRuntimeLicenseTrustKey(
                    keyId = key.keyId,
                    material = key.copyMaterial()
                )
            }
        } catch (_: Throwable) {
            return rejected(ProductionAndroidOfflineResumeTrustFailure.TRUST_INPUT_REJECTED)
        }

        return resolve(
            inputResult = try {
                AndroidProductRuntimeStartupTrustInputFactory.create(
                    observability = observability.runtime,
                    supportedLicenseSchemaVersion =
                        material.deploymentProfile.supportedLicenseSchemaVersion,
                    keys = keys,
                    licenseEnvelope = material.licenseEnvelope
                )
            } catch (_: Throwable) {
                AndroidProductRuntimeStartupTrustInputFactoryResult.Rejected
            },
            assemblyPort = ProductionAndroidOfflineResumeTrustAssemblyPort(
                AndroidProductRuntimeStartupTrustAssembly::create
            )
        )
    }

    internal fun resolve(
        inputResult: AndroidProductRuntimeStartupTrustInputFactoryResult,
        assemblyPort: ProductionAndroidOfflineResumeTrustAssemblyPort
    ): ProductionAndroidOfflineResumeTrustResult {
        val input = when (inputResult) {
            is AndroidProductRuntimeStartupTrustInputFactoryResult.Ready -> inputResult.input
            AndroidProductRuntimeStartupTrustInputFactoryResult.Rejected ->
                return rejected(
                    ProductionAndroidOfflineResumeTrustFailure.TRUST_INPUT_REJECTED
                )
        }

        return when (
            val verified = try {
                assemblyPort.create(input)
            } catch (_: Throwable) {
                return rejected(ProductionAndroidOfflineResumeTrustFailure.FAILED)
            }
        ) {
            is AndroidProductRuntimeStartupTrustAssemblyResult.Ready ->
                ProductionAndroidOfflineResumeTrustResult.Ready(verified.ownership)

            is AndroidProductRuntimeStartupTrustAssemblyResult.VerificationRejected ->
                rejected(ProductionAndroidOfflineResumeTrustFailure.VERIFICATION_REJECTED)

            AndroidProductRuntimeStartupTrustAssemblyResult.Failed ->
                rejected(ProductionAndroidOfflineResumeTrustFailure.FAILED)
        }
    }

    private fun rejected(
        reason: ProductionAndroidOfflineResumeTrustFailure
    ) = ProductionAndroidOfflineResumeTrustResult.Rejected(reason)
}
