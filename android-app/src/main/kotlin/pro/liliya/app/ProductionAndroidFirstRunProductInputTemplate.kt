package pro.liliya.app

import android.content.Context
import java.io.File
import pro.liliya.android.runtime.AndroidProductRuntimeFirstRunKeyChoice
import pro.liliya.android.runtime.AndroidProductRuntimeFirstRunProductInput
import pro.liliya.android.runtime.AndroidProductRuntimeLicenseTrustKey
import pro.liliya.android.runtime.AndroidProductRuntimeObservability
import pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelBudgetInput
import pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelStagingProvisioning
import pro.liliya.android.runtime.AndroidProductRuntimeStartupAdmissionInput
import pro.liliya.android.runtime.AndroidProductRuntimeStartupAuthorityPlan
import pro.liliya.android.runtime.AndroidProductRuntimeStartupPreparedInputOwnerTemplate
import pro.liliya.core.license.LicenseSignedEnvelope

/**
 * Explicit outer-product template for first-run product input.
 *
 * Product Input Template != License Issuance.
 * Product Input Template != Request Authentication.
 * Product Input Template != Authority Minting.
 * Product Input Template != Model Discovery/Selection.
 * Product Input Template != DEK Provisioning.
 *
 * The template owns only caller-approved static product inputs. The signed License envelope and
 * exact already-selected local model are supplied per first-run attempt and are forwarded without
 * substitution into the existing trusted first-run factory/install path.
 */
internal data class ProductionAndroidFirstRunProductInputTemplate(
    val context: Context,
    val observability: AndroidProductRuntimeObservability,
    val supportedLicenseSchemaVersion: Long,
    val licenseTrustKeys: List<AndroidProductRuntimeLicenseTrustKey>,
    val authorityPlan: AndroidProductRuntimeStartupAuthorityPlan,
    val admission: AndroidProductRuntimeStartupAdmissionInput,
    val keyChoice: AndroidProductRuntimeFirstRunKeyChoice,
    val protectedModelBudgets: AndroidProductRuntimeProtectedModelBudgetInput,
    val staging: AndroidProductRuntimeProtectedModelStagingProvisioning,
    val preparedInputOwners: AndroidProductRuntimeStartupPreparedInputOwnerTemplate,
    val semanticDirectoryName: String,
    val cognitiveStorageDirectoryName: String? = null
) {
    fun productInputPort(): ProductionAndroidFirstRunProductInputPort =
        ProductionAndroidFirstRunProductInputPort { envelope, localModelFile ->
            create(envelope, localModelFile)
        }

    internal fun create(
        envelope: LicenseSignedEnvelope,
        localModelFile: File
    ): AndroidProductRuntimeFirstRunProductInput =
        AndroidProductRuntimeFirstRunProductInput(
            context = context,
            observability = observability,
            supportedLicenseSchemaVersion = supportedLicenseSchemaVersion,
            licenseTrustKeys = licenseTrustKeys,
            licenseEnvelope = envelope,
            authorityPlan = authorityPlan,
            admission = admission,
            keyChoice = keyChoice,
            localModelFile = localModelFile,
            protectedModelBudgets = protectedModelBudgets,
            staging = staging,
            preparedInputOwners = preparedInputOwners,
            cognitiveStorageDirectoryName = cognitiveStorageDirectoryName,
            semanticDirectoryName = semanticDirectoryName
        )
}
