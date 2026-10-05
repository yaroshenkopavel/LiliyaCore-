package pro.liliya.app

import android.content.Context
import pro.liliya.android.runtime.AndroidProductRuntimeFirstRunKeyChoice
import pro.liliya.android.runtime.AndroidProductRuntimeFirstRunProductInput
import pro.liliya.android.runtime.AndroidProductRuntimeLicenseTrustKey
import pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelBudgetInput
import pro.liliya.android.runtime.AndroidProductRuntimeProtectedModelStagingProvisioning
import pro.liliya.android.runtime.AndroidProductRuntimeStartupAuthorityPlan
import pro.liliya.android.runtime.AndroidProductRuntimeStartupPreparedInputOwnerTemplate
import pro.liliya.android.runtime.AndroidProductRuntimeStartupTrustOwnership

internal data class ProductionAndroidOfflineResumeProcessPolicy(
    val admission: ProductionAndroidOfflineResumeAdmissionPolicy,
    val authorityPlan: AndroidProductRuntimeStartupAuthorityPlan,
    val protectedModelBudgets: AndroidProductRuntimeProtectedModelBudgetInput,
    val staging: AndroidProductRuntimeProtectedModelStagingProvisioning,
    val preparedInputOwners: AndroidProductRuntimeStartupPreparedInputOwnerTemplate
)

internal enum class ProductionAndroidOfflineResumeProductInputFailure {
    TRUST_REJECTED,
    ADMISSION_REJECTED,
    DIRECTORY_MISMATCH,
    PRINCIPAL_MISMATCH,
    INPUT_REJECTED
}

internal sealed interface ProductionAndroidOfflineResumeProductInputResult {
    data class Ready(
        val input: AndroidProductRuntimeFirstRunProductInput
    ) : ProductionAndroidOfflineResumeProductInputResult

    data class Rejected(
        val reason: ProductionAndroidOfflineResumeProductInputFailure
    ) : ProductionAndroidOfflineResumeProductInputResult
}

internal fun interface ProductionAndroidOfflineResumeTrustPort {
    fun verify(
        material: ProductionAndroidOfflineResumeMaterial
    ): ProductionAndroidOfflineResumeTrustResult
}

internal fun interface ProductionAndroidOfflineResumeAdmissionPort {
    fun build(
        material: ProductionAndroidOfflineResumeMaterial,
        trust: AndroidProductRuntimeStartupTrustOwnership,
        policy: ProductionAndroidOfflineResumeAdmissionPolicy
    ): ProductionAndroidOfflineResumeAdmissionResult
}

internal fun interface ProductionAndroidOfflineResumeInputBuildPort {
    fun build(
        material: ProductionAndroidOfflineResumeMaterial,
        policy: ProductionAndroidOfflineResumeProcessPolicy,
        admission: ProductionAndroidOfflineResumeAdmissionResult.Ready
    ): AndroidProductRuntimeFirstRunProductInput?
}

/**
 * Creates a fresh first-run product input from durable material plus an explicit process-local
 * product policy.
 *
 * Product Input Reconstruction != Authority restoration.
 * Product Input Reconstruction != License trust bypass.
 * Product Input Reconstruction != DEK selection.
 * Product Input Reconstruction != chat replay.
 */
internal object ProductionAndroidOfflineResumeDefaultProductInputFactory {
    fun create(
        context: Context,
        policy: ProductionAndroidOfflineResumeProcessPolicy
    ): ProductionAndroidOfflineResumeProductInputFactory =
        ProductionAndroidOfflineResumeProductInputFactory { material ->
            when (
                val result = build(
                    material = material,
                    policy = policy,
                    trustPort = ProductionAndroidOfflineResumeTrustPort {
                        ProductionAndroidOfflineResumeTrustVerifier.verify(
                            context = context.applicationContext,
                            material = it
                        )
                    },
                    admissionPort = ProductionAndroidOfflineResumeAdmissionPort {
                            exactMaterial,
                            trust,
                            admissionPolicy ->
                        ProductionAndroidOfflineResumeAdmissionBuilder.build(
                            context = context.applicationContext,
                            material = exactMaterial,
                            trust = trust,
                            policy = admissionPolicy
                        )
                    },
                    inputBuildPort = ProductionAndroidOfflineResumeInputBuildPort {
                            exactMaterial,
                            exactPolicy,
                            admission ->
                        buildExactInput(
                            context = context.applicationContext,
                            material = exactMaterial,
                            policy = exactPolicy,
                            admission = admission
                        )
                    }
                )
            ) {
                is ProductionAndroidOfflineResumeProductInputResult.Ready -> result.input
                is ProductionAndroidOfflineResumeProductInputResult.Rejected -> null
            }
        }

    internal fun preflight(
        deploymentSemanticDirectoryName: String,
        deploymentCognitiveStorageDirectoryName: String?,
        resumeSemanticDirectoryName: String,
        resumeCognitiveStorageDirectoryName: String?,
        preparedPrincipal: String,
        admissionPrincipal: String
    ): ProductionAndroidOfflineResumeProductInputFailure? = when {
        deploymentSemanticDirectoryName != resumeSemanticDirectoryName ||
            deploymentCognitiveStorageDirectoryName != resumeCognitiveStorageDirectoryName ->
            ProductionAndroidOfflineResumeProductInputFailure.DIRECTORY_MISMATCH
        preparedPrincipal != admissionPrincipal ->
            ProductionAndroidOfflineResumeProductInputFailure.PRINCIPAL_MISMATCH
        else -> null
    }

    internal fun build(
        material: ProductionAndroidOfflineResumeMaterial,
        policy: ProductionAndroidOfflineResumeProcessPolicy,
        trustPort: ProductionAndroidOfflineResumeTrustPort,
        admissionPort: ProductionAndroidOfflineResumeAdmissionPort,
        inputBuildPort: ProductionAndroidOfflineResumeInputBuildPort
    ): ProductionAndroidOfflineResumeProductInputResult {
        preflight(
            deploymentSemanticDirectoryName =
                material.deploymentProfile.semanticDirectoryName,
            deploymentCognitiveStorageDirectoryName =
                material.deploymentProfile.cognitiveStorageDirectoryName,
            resumeSemanticDirectoryName =
                material.resumeMetadata.semanticDirectoryName,
            resumeCognitiveStorageDirectoryName =
                material.resumeMetadata.cognitiveStorageDirectoryName,
            preparedPrincipal = policy.preparedInputOwners.principal.value,
            admissionPrincipal = policy.admission.principal
        )?.let { return rejected(it) }

        return buildAfterPreflight(
            material = material,
            policy = policy,
            trustPort = trustPort,
            admissionPort = admissionPort,
            inputBuildPort = inputBuildPort
        )
    }

    internal fun buildAfterPreflight(
        material: ProductionAndroidOfflineResumeMaterial,
        policy: ProductionAndroidOfflineResumeProcessPolicy,
        trustPort: ProductionAndroidOfflineResumeTrustPort,
        admissionPort: ProductionAndroidOfflineResumeAdmissionPort,
        inputBuildPort: ProductionAndroidOfflineResumeInputBuildPort
    ): ProductionAndroidOfflineResumeProductInputResult {
        val trust = when (
            val verified = try {
                trustPort.verify(material)
            } catch (_: Throwable) {
                return rejected(
                    ProductionAndroidOfflineResumeProductInputFailure.TRUST_REJECTED
                )
            }
        ) {
            is ProductionAndroidOfflineResumeTrustResult.Ready -> verified.ownership
            is ProductionAndroidOfflineResumeTrustResult.Rejected ->
                return rejected(
                    ProductionAndroidOfflineResumeProductInputFailure.TRUST_REJECTED
                )
        }

        val admission = when (
            val built = try {
                admissionPort.build(material, trust, policy.admission)
            } catch (_: Throwable) {
                return rejected(
                    ProductionAndroidOfflineResumeProductInputFailure.ADMISSION_REJECTED
                )
            }
        ) {
            is ProductionAndroidOfflineResumeAdmissionResult.Ready -> built
            is ProductionAndroidOfflineResumeAdmissionResult.Rejected ->
                return rejected(
                    ProductionAndroidOfflineResumeProductInputFailure.ADMISSION_REJECTED
                )
        }

        val input = try {
            inputBuildPort.build(material, policy, admission)
        } catch (_: Throwable) {
            null
        } ?: return rejected(
            ProductionAndroidOfflineResumeProductInputFailure.INPUT_REJECTED
        )

        return ProductionAndroidOfflineResumeProductInputResult.Ready(input)
    }

    internal fun buildExactInput(
        context: Context,
        material: ProductionAndroidOfflineResumeMaterial,
        policy: ProductionAndroidOfflineResumeProcessPolicy,
        admission: ProductionAndroidOfflineResumeAdmissionResult.Ready
    ): AndroidProductRuntimeFirstRunProductInput? = try {
        val observability = ProductionAndroidAppObservability.create(context).also {
            it.installLoggerWriter()
        }

        val trustKeys = material.deploymentProfile.copyLicenseTrustKeys().map { key ->
            AndroidProductRuntimeLicenseTrustKey(
                keyId = key.keyId,
                material = key.copyMaterial()
            )
        }

        val metadata = material.resumeMetadata
        val prepared = policy.preparedInputOwners.copy(
            memoryStoreId = metadata.memoryStoreId,
            knowledgeStoreId = metadata.knowledgeStoreId,
            learningMutationStoreId = metadata.learningMutationStoreId
        )

        AndroidProductRuntimeFirstRunProductInput(
            context = context.applicationContext,
            observability = observability.runtime,
            supportedLicenseSchemaVersion =
                material.deploymentProfile.supportedLicenseSchemaVersion,
            licenseTrustKeys = trustKeys,
            licenseEnvelope = material.licenseEnvelope,
            authorityPlan = policy.authorityPlan,
            admission = admission.input,
            keyChoice = AndroidProductRuntimeFirstRunKeyChoice.RestoreExact(
                dekId = metadata.activeDek.id.value,
                dekGeneration = metadata.activeDek.generation.value
            ),
            localModelFile = material.localModelFile,
            protectedModelBudgets = policy.protectedModelBudgets,
            staging = policy.staging,
            preparedInputOwners = prepared,
            cognitiveStorageDirectoryName =
                metadata.cognitiveStorageDirectoryName,
            semanticDirectoryName = metadata.semanticDirectoryName
        )
    } catch (_: Throwable) {
        null
    }

    private fun rejected(
        reason: ProductionAndroidOfflineResumeProductInputFailure
    ) = ProductionAndroidOfflineResumeProductInputResult.Rejected(reason)
}
