package pro.liliya.android.runtime

import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import pro.liliya.core.protectedmodel.ModelDekGeneration
import pro.liliya.core.protectedmodel.ModelDekId
import pro.liliya.core.protectedmodel.ModelDekReference
import pro.liliya.core.protectedmodel.PersistentProtectedModelDekBinding
import pro.liliya.core.protectedmodel.PersistentProtectedModelDekFailure
import pro.liliya.core.protectedmodel.ProtectedModelDekProvisionAndRegisterResult
import pro.liliya.core.protectedmodel.ProtectedModelDekProvisioningFailure
import pro.liliya.core.protectedmodel.ProtectedModelGeneration
import pro.liliya.core.protectedmodel.ProtectedModelPackageId
import pro.liliya.core.protectedmodel.ProtectedModelReference

class AndroidProductRuntimeProtectedModelFirstRunPreparationContractTest {
    @Test
    fun verification_rejection_never_calls_dek_provisioning_or_staging() {
        var inspectCalled = false
        var provisionCalled = false
        var stagingCalled = false

        val result = AndroidProductRuntimeProtectedModelFirstRunPreparation.coordinate(
            verifyPort = AndroidProductRuntimeProtectedModelVerifyPort {
                AndroidProductRuntimeProtectedModelVerificationResult.Rejected(
                    AndroidProductRuntimeProtectedModelFirstRunPreparationFailure
                        .PACKAGE_VERIFICATION_REJECTED
                )
            },
            inspectPort = AndroidProductRuntimeProtectedModelDekInspectPort { _, _ ->
                inspectCalled = true
                null
            },
            provisionPort = AndroidProductRuntimeProtectedModelDekProvisionPort { _, _ ->
                provisionCalled = true
                error("must not provision unverified package")
            },
            stagingPort = AndroidProductRuntimeProtectedModelStagingBuildPort {
                stagingCalled = true
                "staging"
            }
        )

        assertEquals(false, inspectCalled)
        assertEquals(false, provisionCalled)
        assertEquals(false, stagingCalled)
        assertEquals(
            AndroidProductRuntimeProtectedModelFirstRunPreparationFailure
                .PACKAGE_VERIFICATION_REJECTED,
            assertIs<
                AndroidProductRuntimeProtectedModelPreparationCoordinationResult.Rejected
            >(result).reason
        )
    }

    @Test
    fun existing_exact_wrapped_dek_is_reused_without_provider_call() {
        val references = references()
        var provisionCalled = false

        val result = AndroidProductRuntimeProtectedModelFirstRunPreparation.coordinate(
            verifyPort = verified(references),
            inspectPort = AndroidProductRuntimeProtectedModelDekInspectPort { model, dek ->
                PersistentProtectedModelDekBinding(model, dek)
            },
            provisionPort = AndroidProductRuntimeProtectedModelDekProvisionPort { _, _ ->
                provisionCalled = true
                error("existing exact DEK must be reusable offline")
            },
            stagingPort = AndroidProductRuntimeProtectedModelStagingBuildPort {
                "staging"
            }
        )

        val ready = assertIs<
            AndroidProductRuntimeProtectedModelPreparationCoordinationResult.Ready<String>
        >(result)
        assertEquals(false, provisionCalled)
        assertEquals(true, ready.reusedExistingDek)
        assertEquals(references, ready.references)
        assertEquals("staging", ready.staging)
    }

    @Test
    fun missing_dek_is_provisioned_for_exact_verified_references_before_staging() {
        val references = references()
        var observedModel: ProtectedModelReference? = null
        var observedDek: ModelDekReference? = null
        val order = mutableListOf<String>()

        val result = AndroidProductRuntimeProtectedModelFirstRunPreparation.coordinate(
            verifyPort = AndroidProductRuntimeProtectedModelVerifyPort {
                order += "verify"
                AndroidProductRuntimeProtectedModelVerificationResult.Ready(references)
            },
            inspectPort = AndroidProductRuntimeProtectedModelDekInspectPort { _, _ ->
                order += "inspect"
                null
            },
            provisionPort = AndroidProductRuntimeProtectedModelDekProvisionPort { model, dek ->
                order += "provision"
                observedModel = model
                observedDek = dek
                ProtectedModelDekProvisionAndRegisterResult.Registered(
                    PersistentProtectedModelDekBinding(model, dek)
                )
            },
            stagingPort = AndroidProductRuntimeProtectedModelStagingBuildPort {
                order += "staging"
                7
            }
        )

        val ready = assertIs<
            AndroidProductRuntimeProtectedModelPreparationCoordinationResult.Ready<Int>
        >(result)
        assertEquals(listOf("verify", "inspect", "provision", "staging"), order)
        assertEquals(references.model, observedModel)
        assertEquals(references.dek, observedDek)
        assertEquals(false, ready.reusedExistingDek)
        assertEquals(7, ready.staging)
    }

    @Test
    fun provisioning_rejection_prevents_staging() {
        val references = references()
        var stagingCalled = false

        val result = AndroidProductRuntimeProtectedModelFirstRunPreparation.coordinate(
            verifyPort = verified(references),
            inspectPort = AndroidProductRuntimeProtectedModelDekInspectPort { _, _ -> null },
            provisionPort = AndroidProductRuntimeProtectedModelDekProvisionPort { _, _ ->
                ProtectedModelDekProvisionAndRegisterResult.ProvisioningRejected(
                    ProtectedModelDekProvisioningFailure.REJECTED
                )
            },
            stagingPort = AndroidProductRuntimeProtectedModelStagingBuildPort {
                stagingCalled = true
                "must-not-run"
            }
        )

        assertEquals(false, stagingCalled)
        assertEquals(
            AndroidProductRuntimeProtectedModelFirstRunPreparationFailure
                .DEK_PROVISIONING_REJECTED,
            assertIs<
                AndroidProductRuntimeProtectedModelPreparationCoordinationResult.Rejected
            >(result).reason
        )
    }

    @Test
    fun mismatched_registered_binding_is_fail_closed() {
        val references = references()
        val wrongModel = ProtectedModelReference(
            ProtectedModelPackageId("other-model"),
            ProtectedModelGeneration(1)
        )

        val result = AndroidProductRuntimeProtectedModelFirstRunPreparation.coordinate(
            verifyPort = verified(references),
            inspectPort = AndroidProductRuntimeProtectedModelDekInspectPort { _, _ -> null },
            provisionPort = AndroidProductRuntimeProtectedModelDekProvisionPort { _, _ ->
                ProtectedModelDekProvisionAndRegisterResult.Registered(
                    PersistentProtectedModelDekBinding(
                        model = wrongModel,
                        dek = references.dek
                    )
                )
            },
            stagingPort = AndroidProductRuntimeProtectedModelStagingBuildPort {
                error("mismatched binding must not stage")
            }
        )

        assertEquals(
            AndroidProductRuntimeProtectedModelFirstRunPreparationFailure
                .DEK_PROVISIONING_FAILED,
            assertIs<
                AndroidProductRuntimeProtectedModelPreparationCoordinationResult.Rejected
            >(result).reason
        )
    }

    @Test
    fun store_rejection_is_preserved_as_provisioning_rejection() {
        val references = references()

        val result = AndroidProductRuntimeProtectedModelFirstRunPreparation.coordinate(
            verifyPort = verified(references),
            inspectPort = AndroidProductRuntimeProtectedModelDekInspectPort { _, _ -> null },
            provisionPort = AndroidProductRuntimeProtectedModelDekProvisionPort { _, _ ->
                ProtectedModelDekProvisionAndRegisterResult.StoreRejected(
                    PersistentProtectedModelDekFailure.STALE_DEK_OWNERSHIP
                )
            },
            stagingPort = AndroidProductRuntimeProtectedModelStagingBuildPort {
                error("rejected store must not stage")
            }
        )

        assertEquals(
            AndroidProductRuntimeProtectedModelFirstRunPreparationFailure
                .DEK_PROVISIONING_REJECTED,
            assertIs<
                AndroidProductRuntimeProtectedModelPreparationCoordinationResult.Rejected
            >(result).reason
        )
    }

    private fun verified(
        references: AndroidProductRuntimeVerifiedProtectedModelReferences
    ): AndroidProductRuntimeProtectedModelVerifyPort =
        AndroidProductRuntimeProtectedModelVerifyPort {
            AndroidProductRuntimeProtectedModelVerificationResult.Ready(references)
        }

    private fun references(): AndroidProductRuntimeVerifiedProtectedModelReferences =
        AndroidProductRuntimeVerifiedProtectedModelReferences(
            model = ProtectedModelReference(
                ProtectedModelPackageId("verified-model"),
                ProtectedModelGeneration(4)
            ),
            dek = ModelDekReference(
                ModelDekId("verified-dek"),
                ModelDekGeneration(7)
            )
        )
}
