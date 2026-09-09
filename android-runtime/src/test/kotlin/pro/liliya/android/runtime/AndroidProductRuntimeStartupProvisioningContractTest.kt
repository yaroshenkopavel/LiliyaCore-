package pro.liliya.android.runtime

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import pro.liliya.core.encryption.CognitiveDekGeneration
import pro.liliya.core.encryption.CognitiveDekId
import pro.liliya.core.encryption.CognitiveDekReference

class AndroidProductRuntimeStartupProvisioningContractTest {
    @Test
    fun admission_rejection_stops_all_provisioning() {
        val calls = mutableListOf<String>()
        val ports = ports(
            admission = {
                calls += "admission"
                AndroidProductRuntimeAdmissionResult.Rejected(
                    AndroidProductRuntimeAdmissionFailure.AUTHORITY_DENIED
                )
            },
            activeDek = {
                calls += "dek"
                AndroidProductRuntimeStartupPreparationResult.Rejected
            },
            semantic = {
                calls += "semantic"
                AndroidProductRuntimeStartupPreparationResult.Rejected
            },
            model = {
                calls += "model"
                AndroidProductRuntimeStartupPreparationResult.Rejected
            }
        )

        val result = AndroidProductRuntimeStartupProvisioner.prepare(ports)

        assertIs<AndroidProductRuntimeStartupProvisioningResult.AdmissionRejected>(result)
        assertEquals(listOf("admission"), calls)
    }

    @Test
    fun active_dek_rejection_stops_semantic_and_model_work() {
        val calls = mutableListOf<String>()
        val ports = ports(
            admission = {
                calls += "admission"
                admitted()
            },
            activeDek = {
                calls += "dek"
                AndroidProductRuntimeStartupPreparationResult.Rejected
            },
            semantic = {
                calls += "semantic"
                AndroidProductRuntimeStartupPreparationResult.Rejected
            },
            model = {
                calls += "model"
                AndroidProductRuntimeStartupPreparationResult.Rejected
            }
        )

        val result = AndroidProductRuntimeStartupProvisioner.prepare(ports)

        val rejected = assertIs<AndroidProductRuntimeStartupProvisioningResult.Rejected>(result)
        assertEquals(AndroidProductRuntimeStartupProvisioningFailure.ACTIVE_DEK_REJECTED, rejected.reason)
        assertEquals(listOf("admission", "dek"), calls)
    }

    @Test
    fun semantic_rejection_stops_model_work() {
        val calls = mutableListOf<String>()
        val ports = ports(
            admission = {
                calls += "admission"
                admitted()
            },
            activeDek = {
                calls += "dek"
                AndroidProductRuntimeStartupPreparationResult.Ready(dek())
            },
            semantic = {
                calls += "semantic"
                AndroidProductRuntimeStartupPreparationResult.Rejected
            },
            model = {
                calls += "model"
                AndroidProductRuntimeStartupPreparationResult.Rejected
            }
        )

        val result = AndroidProductRuntimeStartupProvisioner.prepare(ports)

        val rejected = assertIs<AndroidProductRuntimeStartupProvisioningResult.Rejected>(result)
        assertEquals(AndroidProductRuntimeStartupProvisioningFailure.SEMANTIC_REJECTED, rejected.reason)
        assertEquals(listOf("admission", "dek", "semantic"), calls)
    }

    @Test
    fun model_rejection_stops_prepared_input_creation() {
        val calls = mutableListOf<String>()
        val ports = ports(
            admission = {
                calls += "admission"
                admitted()
            },
            activeDek = {
                calls += "dek"
                AndroidProductRuntimeStartupPreparationResult.Ready(dek())
            },
            semantic = {
                calls += "semantic"
                AndroidProductRuntimeStartupPreparationResult.Ready(
                    AndroidProductRuntimeSemanticArtifacts(
                        root = File("semantic-root"),
                        encoderFile = File("semantic-root/encoder.onnx")
                    )
                )
            },
            model = {
                calls += "model"
                AndroidProductRuntimeStartupPreparationResult.Rejected
            },
            preparedInputs = { _, _, _ ->
                calls += "inputs"
                AndroidProductRuntimeStartupPreparationResult.Rejected
            }
        )

        val result = AndroidProductRuntimeStartupProvisioner.prepare(ports)

        val rejected = assertIs<AndroidProductRuntimeStartupProvisioningResult.Rejected>(result)
        assertEquals(AndroidProductRuntimeStartupProvisioningFailure.MODEL_REJECTED, rejected.reason)
        assertEquals(listOf("admission", "dek", "semantic", "model"), calls)
    }

    @Test
    fun unexpected_exception_is_bounded_and_stops_following_work() {
        val calls = mutableListOf<String>()
        val ports = ports(
            admission = { admitted() },
            activeDek = { AndroidProductRuntimeStartupPreparationResult.Ready(dek()) },
            semantic = {
                calls += "semantic"
                error("private semantic failure")
            },
            model = {
                calls += "model"
                AndroidProductRuntimeStartupPreparationResult.Rejected
            }
        )

        val result = AndroidProductRuntimeStartupProvisioner.prepare(ports)

        val rejected = assertIs<AndroidProductRuntimeStartupProvisioningResult.Rejected>(result)
        assertEquals(AndroidProductRuntimeStartupProvisioningFailure.INTERNAL_FAILURE, rejected.reason)
        assertEquals(listOf("semantic"), calls)
    }


    @Test
    fun active_dek_adapter_preserves_exact_ready_reference() {
        val exact = dek()
        val adapter = AndroidProductRuntimeStartupActiveDekAdapter(
            AndroidProductRuntimeStartupDekSetupPort {
                pro.liliya.android.cognitivestorage.AndroidCognitiveStorageFirstRunKeySetupResult.Ready(exact)
            }
        )

        val result = adapter.prepare()

        assertEquals(
            exact,
            assertIs<AndroidProductRuntimeStartupPreparationResult.Ready<CognitiveDekReference>>(result).value
        )
    }

    @Test
    fun active_dek_adapter_maps_rejection_and_exception_to_bounded_rejection() {
        val rejected = AndroidProductRuntimeStartupActiveDekAdapter(
            AndroidProductRuntimeStartupDekSetupPort {
                pro.liliya.android.cognitivestorage.AndroidCognitiveStorageFirstRunKeySetupResult.Rejected(
                    pro.liliya.android.cognitivestorage.AndroidCognitiveStorageFirstRunKeySetupFailure.DEK_REFERENCE_MISSING
                )
            }
        )
        val failed = AndroidProductRuntimeStartupActiveDekAdapter(
            AndroidProductRuntimeStartupDekSetupPort { error("private key setup failure") }
        )

        assertIs<AndroidProductRuntimeStartupPreparationResult.Rejected>(rejected.prepare())
        assertIs<AndroidProductRuntimeStartupPreparationResult.Rejected>(failed.prepare())
    }



    @Test
    fun semantic_adapter_preserves_exact_resolved_location() {
        val root = File("semantic-root")
        val encoder = File(root, "encoder.onnx")
        val adapter = AndroidProductRuntimeStartupSemanticAdapter(
            AndroidProductRuntimeStartupSemanticResolvePort {
                pro.liliya.android.semanticprovider.AndroidOfflineSemanticProvisionedLocationResult.Ready(
                    root = root,
                    encoderFile = encoder
                )
            }
        )

        val result = adapter.prepare()

        val ready = assertIs<AndroidProductRuntimeStartupPreparationResult.Ready<AndroidProductRuntimeSemanticArtifacts>>(result)
        assertEquals(root, ready.value.root)
        assertEquals(encoder, ready.value.encoderFile)
    }

    @Test
    fun semantic_adapter_maps_rejection_and_exception_to_bounded_rejection() {
        val rejected = AndroidProductRuntimeStartupSemanticAdapter(
            AndroidProductRuntimeStartupSemanticResolvePort {
                pro.liliya.android.semanticprovider.AndroidOfflineSemanticProvisionedLocationResult.MissingOrRejected
            }
        )
        val failed = AndroidProductRuntimeStartupSemanticAdapter(
            AndroidProductRuntimeStartupSemanticResolvePort { error("private semantic resolver failure") }
        )

        assertIs<AndroidProductRuntimeStartupPreparationResult.Rejected>(rejected.prepare())
        assertIs<AndroidProductRuntimeStartupPreparationResult.Rejected>(failed.prepare())
    }

    @Test
    fun local_model_adapter_stops_before_staging_when_local_package_is_rejected() {
        var stagingCalls = 0
        val adapter = AndroidProductRuntimeStartupLocalModelAdapter(
            openPort = AndroidProductRuntimeStartupLocalPackageOpenPort {
                ProductProtectedModelLocalPackageOpenResult.Rejected(
                    ProductProtectedModelLocalPackageFailure.SOURCE_REJECTED
                )
            },
            stagingPort = AndroidProductRuntimeStartupStagingProvisionPort {
                stagingCalls += 1
                ProductGenerationStagingProvisionResult.Rejected(
                    ProductGenerationStagingProvisionFailure.PACKAGE_REJECTED
                )
            }
        )

        assertIs<AndroidProductRuntimeStartupPreparationResult.Rejected>(adapter.prepare())
        assertEquals(0, stagingCalls)
    }

    private fun ports(
        admission: () -> AndroidProductRuntimeAdmissionResult,
        activeDek: () -> AndroidProductRuntimeStartupPreparationResult<CognitiveDekReference>,
        semantic: () -> AndroidProductRuntimeStartupPreparationResult<AndroidProductRuntimeSemanticArtifacts>,
        model: () -> AndroidProductRuntimeStartupPreparationResult<pro.liliya.core.protectedmodel.LargeProtectedModelStagedSourceOwnership>,
        preparedInputs: AndroidProductRuntimeStartupPreparedInputsPort =
            AndroidProductRuntimeStartupPreparedInputsPort { _, _, _ ->
                error("prepared inputs must not be called")
            }
    ): AndroidProductRuntimeStartupProvisioningPorts =
        AndroidProductRuntimeStartupProvisioningPorts(
            admission = AndroidProductRuntimeStartupAdmissionPort(admission),
            activeDek = AndroidProductRuntimeStartupActiveDekPort(activeDek),
            semantic = AndroidProductRuntimeStartupSemanticPort(semantic),
            model = AndroidProductRuntimeStartupModelPort(model),
            preparedInputs = preparedInputs
        )

    private fun admitted(): AndroidProductRuntimeAdmissionResult.Admitted =
        AndroidProductRuntimeAdmissionGate.admit(
            AndroidProductRuntimeAdmissionDecisionPort {
                AndroidProductRuntimeAdmissionDecision.Authorized
            }
        ) as AndroidProductRuntimeAdmissionResult.Admitted

    private fun dek(): CognitiveDekReference =
        CognitiveDekReference(
            id = CognitiveDekId("startup-dek"),
            generation = CognitiveDekGeneration(1)
        )
}
