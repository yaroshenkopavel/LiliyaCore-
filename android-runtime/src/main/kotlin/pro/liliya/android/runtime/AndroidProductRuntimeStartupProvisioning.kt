package pro.liliya.android.runtime

import java.io.File
import pro.liliya.core.encryption.CognitiveDekReference
import pro.liliya.core.protectedmodel.LargeProtectedModelStagedSourceOwnership

sealed interface AndroidProductRuntimeStartupPreparationResult<out T> {
    data class Ready<T>(val value: T) : AndroidProductRuntimeStartupPreparationResult<T>
    data object Rejected : AndroidProductRuntimeStartupPreparationResult<Nothing>
}

data class AndroidProductRuntimeSemanticArtifacts(
    val root: File,
    val encoderFile: File
)

fun interface AndroidProductRuntimeStartupAdmissionPort {
    fun admit(): AndroidProductRuntimeAdmissionResult
}

fun interface AndroidProductRuntimeStartupActiveDekPort {
    fun prepare(): AndroidProductRuntimeStartupPreparationResult<CognitiveDekReference>
}

fun interface AndroidProductRuntimeStartupSemanticPort {
    fun prepare(): AndroidProductRuntimeStartupPreparationResult<AndroidProductRuntimeSemanticArtifacts>
}

fun interface AndroidProductRuntimeStartupModelPort {
    fun prepare(): AndroidProductRuntimeStartupPreparationResult<LargeProtectedModelStagedSourceOwnership>
}

fun interface AndroidProductRuntimeStartupPreparedInputsPort {
    fun prepare(
        activeDek: CognitiveDekReference,
        semantic: AndroidProductRuntimeSemanticArtifacts,
        stagedModel: LargeProtectedModelStagedSourceOwnership
    ): AndroidProductRuntimeStartupPreparationResult<AndroidProductRuntimeHostPreparedInputs>
}

data class AndroidProductRuntimeStartupProvisioningPorts(
    val admission: AndroidProductRuntimeStartupAdmissionPort,
    val activeDek: AndroidProductRuntimeStartupActiveDekPort,
    val semantic: AndroidProductRuntimeStartupSemanticPort,
    val model: AndroidProductRuntimeStartupModelPort,
    val preparedInputs: AndroidProductRuntimeStartupPreparedInputsPort
)

enum class AndroidProductRuntimeStartupProvisioningFailure {
    ACTIVE_DEK_REJECTED,
    SEMANTIC_REJECTED,
    MODEL_REJECTED,
    PREPARED_INPUTS_REJECTED,
    PREPARED_INPUTS_MISMATCH,
    INTERNAL_FAILURE
}

sealed interface AndroidProductRuntimeStartupProvisioningResult {
    data class Ready(
        val admission: AndroidProductRuntimeAdmissionResult.Admitted,
        val inputs: AndroidProductRuntimeHostPreparedInputs
    ) : AndroidProductRuntimeStartupProvisioningResult

    data class AdmissionRejected(
        val result: AndroidProductRuntimeAdmissionResult
    ) : AndroidProductRuntimeStartupProvisioningResult

    data class Rejected(
        val reason: AndroidProductRuntimeStartupProvisioningFailure
    ) : AndroidProductRuntimeStartupProvisioningResult
}

/**
 * Bounded startup orchestration over already-authoritative production owners.
 *
 * Startup Provisioning != License Authority.
 * Startup Provisioning != Capability Authority.
 * Startup Provisioning != Model Selection.
 * Startup Provisioning != DEK Selection Heuristic.
 * Startup Provisioning != Runtime State Authority.
 */
object AndroidProductRuntimeStartupProvisioner {
    fun prepare(
        ports: AndroidProductRuntimeStartupProvisioningPorts
    ): AndroidProductRuntimeStartupProvisioningResult {
        val admission = try {
            ports.admission.admit()
        } catch (_: Exception) {
            return rejected(AndroidProductRuntimeStartupProvisioningFailure.INTERNAL_FAILURE)
        }
        val admitted = admission as? AndroidProductRuntimeAdmissionResult.Admitted
            ?: return AndroidProductRuntimeStartupProvisioningResult.AdmissionRejected(admission)

        val activeDek = when (val result = try {
            ports.activeDek.prepare()
        } catch (_: Exception) {
            return rejected(AndroidProductRuntimeStartupProvisioningFailure.INTERNAL_FAILURE)
        }) {
            is AndroidProductRuntimeStartupPreparationResult.Ready -> result.value
            AndroidProductRuntimeStartupPreparationResult.Rejected ->
                return rejected(AndroidProductRuntimeStartupProvisioningFailure.ACTIVE_DEK_REJECTED)
        }

        val semantic = when (val result = try {
            ports.semantic.prepare()
        } catch (_: Exception) {
            return rejected(AndroidProductRuntimeStartupProvisioningFailure.INTERNAL_FAILURE)
        }) {
            is AndroidProductRuntimeStartupPreparationResult.Ready -> result.value
            AndroidProductRuntimeStartupPreparationResult.Rejected ->
                return rejected(AndroidProductRuntimeStartupProvisioningFailure.SEMANTIC_REJECTED)
        }

        val stagedModel = when (val result = try {
            ports.model.prepare()
        } catch (_: Exception) {
            return rejected(AndroidProductRuntimeStartupProvisioningFailure.INTERNAL_FAILURE)
        }) {
            is AndroidProductRuntimeStartupPreparationResult.Ready -> result.value
            AndroidProductRuntimeStartupPreparationResult.Rejected ->
                return rejected(AndroidProductRuntimeStartupProvisioningFailure.MODEL_REJECTED)
        }

        val inputs = when (val result = try {
            ports.preparedInputs.prepare(activeDek, semantic, stagedModel)
        } catch (_: Exception) {
            return rejected(AndroidProductRuntimeStartupProvisioningFailure.INTERNAL_FAILURE)
        }) {
            is AndroidProductRuntimeStartupPreparationResult.Ready -> result.value
            AndroidProductRuntimeStartupPreparationResult.Rejected ->
                return rejected(
                    AndroidProductRuntimeStartupProvisioningFailure.PREPARED_INPUTS_REJECTED
                )
        }

        if (
            inputs.activeDek != activeDek ||
            inputs.semanticRoot != semantic.root ||
            inputs.semanticEncoderFile != semantic.encoderFile ||
            inputs.stagedModel !== stagedModel
        ) {
            return rejected(AndroidProductRuntimeStartupProvisioningFailure.PREPARED_INPUTS_MISMATCH)
        }

        return AndroidProductRuntimeStartupProvisioningResult.Ready(
            admission = admitted,
            inputs = inputs
        )
    }

    private fun rejected(
        reason: AndroidProductRuntimeStartupProvisioningFailure
    ): AndroidProductRuntimeStartupProvisioningResult.Rejected =
        AndroidProductRuntimeStartupProvisioningResult.Rejected(reason)
}
internal fun interface AndroidProductRuntimeStartupDekSetupPort {
    fun prepare(): pro.liliya.android.cognitivestorage.AndroidCognitiveStorageFirstRunKeySetupResult
}

class AndroidProductRuntimeStartupActiveDekAdapter internal constructor(
    private val setupPort: AndroidProductRuntimeStartupDekSetupPort
) : AndroidProductRuntimeStartupActiveDekPort {
    constructor(
        setup: pro.liliya.android.cognitivestorage.AndroidCognitiveStorageFirstRunKeySetup,
        request: pro.liliya.android.cognitivestorage.AndroidCognitiveStorageFirstRunKeySetupRequest
    ) : this(
        AndroidProductRuntimeStartupDekSetupPort { setup.prepare(request) }
    )

    override fun prepare(): AndroidProductRuntimeStartupPreparationResult<CognitiveDekReference> =
        when (val result = try {
            setupPort.prepare()
        } catch (_: Exception) {
            return AndroidProductRuntimeStartupPreparationResult.Rejected
        }) {
            is pro.liliya.android.cognitivestorage.AndroidCognitiveStorageFirstRunKeySetupResult.Ready ->
                AndroidProductRuntimeStartupPreparationResult.Ready(result.activeDek)
            is pro.liliya.android.cognitivestorage.AndroidCognitiveStorageFirstRunKeySetupResult.Rejected ->
                AndroidProductRuntimeStartupPreparationResult.Rejected
        }
}

internal fun interface AndroidProductRuntimeStartupLocalPackageOpenPort {
    fun open(): ProductProtectedModelLocalPackageOpenResult
}

internal fun interface AndroidProductRuntimeStartupStagingProvisionPort {
    fun provision(
        opened: ProductProtectedModelLocalPackageOpenResult.Opened
    ): ProductGenerationStagingProvisionResult
}

class AndroidProductRuntimeStartupLocalModelAdapter internal constructor(
    private val openPort: AndroidProductRuntimeStartupLocalPackageOpenPort,
    private val stagingPort: AndroidProductRuntimeStartupStagingProvisionPort
) : AndroidProductRuntimeStartupModelPort {
    constructor(
        file: File,
        manifestBudgets: pro.liliya.core.protectedmodel.LargeProtectedModelResourceBudgets,
        packageBudgets: pro.liliya.core.protectedmodel.LargeProtectedModelPackageBudgets,
        containerBudgets: ProductProtectedModelLocalPackageBudgets,
        stagingProvisioner: ProductGenerationStagingProvisioner
    ) : this(
        openPort = AndroidProductRuntimeStartupLocalPackageOpenPort {
            ProductProtectedModelLocalPackage.open(
                file = file,
                manifestBudgets = manifestBudgets,
                packageBudgets = packageBudgets,
                containerBudgets = containerBudgets
            )
        },
        stagingPort = AndroidProductRuntimeStartupStagingProvisionPort { opened ->
            stagingProvisioner.provision(opened.envelope, opened.source)
        }
    )

    override fun prepare(): AndroidProductRuntimeStartupPreparationResult<LargeProtectedModelStagedSourceOwnership> {
        val opened = try {
            openPort.open()
        } catch (_: Exception) {
            return AndroidProductRuntimeStartupPreparationResult.Rejected
        }
        val exact = opened as? ProductProtectedModelLocalPackageOpenResult.Opened
            ?: return AndroidProductRuntimeStartupPreparationResult.Rejected

        return when (val staged = try {
            stagingPort.provision(exact)
        } catch (_: Exception) {
            return AndroidProductRuntimeStartupPreparationResult.Rejected
        }) {
            is ProductGenerationStagingProvisionResult.Ready ->
                AndroidProductRuntimeStartupPreparationResult.Ready(staged.ownership)
            is ProductGenerationStagingProvisionResult.Rejected ->
                AndroidProductRuntimeStartupPreparationResult.Rejected
        }
    }
}

internal fun interface AndroidProductRuntimeStartupSemanticResolvePort {
    fun resolve(): pro.liliya.android.semanticprovider.AndroidOfflineSemanticProvisionedLocationResult
}

class AndroidProductRuntimeStartupSemanticAdapter internal constructor(
    private val resolvePort: AndroidProductRuntimeStartupSemanticResolvePort
) : AndroidProductRuntimeStartupSemanticPort {
    constructor(
        context: android.content.Context,
        provisioner: pro.liliya.android.semanticprovider.AndroidOfflineSemanticArtifactProvisioner,
        directoryName: String = pro.liliya.android.semanticprovider.AndroidOfflineSemanticArtifactProvisioner.DEFAULT_DIRECTORY
    ) : this(
        AndroidProductRuntimeStartupSemanticResolvePort {
            provisioner.resolveProvisioned(context, directoryName)
        }
    )

    override fun prepare(): AndroidProductRuntimeStartupPreparationResult<AndroidProductRuntimeSemanticArtifacts> =
        when (val result = try {
            resolvePort.resolve()
        } catch (_: Exception) {
            return AndroidProductRuntimeStartupPreparationResult.Rejected
        }) {
            is pro.liliya.android.semanticprovider.AndroidOfflineSemanticProvisionedLocationResult.Ready ->
                AndroidProductRuntimeStartupPreparationResult.Ready(
                    AndroidProductRuntimeSemanticArtifacts(
                        root = result.root,
                        encoderFile = result.encoderFile
                    )
                )
            pro.liliya.android.semanticprovider.AndroidOfflineSemanticProvisionedLocationResult.MissingOrRejected,
            pro.liliya.android.semanticprovider.AndroidOfflineSemanticProvisionedLocationResult.Failed ->
                AndroidProductRuntimeStartupPreparationResult.Rejected
        }
}
