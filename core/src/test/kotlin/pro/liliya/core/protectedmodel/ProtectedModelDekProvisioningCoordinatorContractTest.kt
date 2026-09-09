package pro.liliya.core.protectedmodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProtectedModelDekProvisioningCoordinatorContractTest {
    @Test
    fun exact_provisioning_evidence_is_immediately_forwarded_to_wrapped_registration() {
        val request = request()
        val material = ProtectedModelDekMaterial(ByteArray(32) { (it + 1).toByte() })
        var observed: ProtectedModelDekMaterial? = null

        val result = ProtectedModelDekProvisioningCoordinator.coordinate(
            request = request,
            provision = {
                ProtectedModelDekProvisioningResult.Provisioned(
                    ProtectedModelDekProvisioningEvidence(
                        model = request.model,
                        dek = request.dek,
                        material = material
                    )
                )
            },
            registration = ProtectedModelDekRegistrationPort {
                observed = it
                PersistentProtectedModelDekRegistrationResult.Registered(
                    PersistentProtectedModelDekBinding(request.model, request.dek)
                )
            }
        )

        assertTrue(observed === material)
        assertEquals(
            PersistentProtectedModelDekBinding(request.model, request.dek),
            assertIs<ProtectedModelDekProvisionAndRegisterResult.Registered>(result).binding
        )
    }

    @Test
    fun mismatched_provider_reference_is_rejected_before_registration() {
        val request = request()
        var registrationCalled = false

        val result = ProtectedModelDekProvisioningCoordinator.coordinate(
            request = request,
            provision = {
                ProtectedModelDekProvisioningResult.Provisioned(
                    ProtectedModelDekProvisioningEvidence(
                        model = request.model.copy(generation = ProtectedModelGeneration(99)),
                        dek = request.dek,
                        material = ProtectedModelDekMaterial(ByteArray(32) { 7 })
                    )
                )
            },
            registration = ProtectedModelDekRegistrationPort {
                registrationCalled = true
                error("must not register mismatched evidence")
            }
        )

        assertEquals(false, registrationCalled)
        assertEquals(
            ProtectedModelDekProvisioningFailure.REFERENCE_MISMATCH,
            assertIs<ProtectedModelDekProvisionAndRegisterResult.ProvisioningRejected>(result).reason
        )
    }

    @Test
    fun provider_failure_does_not_call_registration() {
        val request = request()
        var registrationCalled = false

        val result = ProtectedModelDekProvisioningCoordinator.coordinate(
            request = request,
            provision = {
                ProtectedModelDekProvisioningResult.Failed(
                    ProtectedModelDekProvisioningFailure.PROVIDER_FAILED,
                    IllegalStateException("private provider failure")
                )
            },
            registration = ProtectedModelDekRegistrationPort {
                registrationCalled = true
                error("must not register failed provisioning")
            }
        )

        assertEquals(false, registrationCalled)
        val failed = assertIs<ProtectedModelDekProvisionAndRegisterResult.Failed>(result)
        assertEquals(ProtectedModelDekProvisioningFailure.PROVIDER_FAILED, failed.reason)
        assertTrue(failed.throwable is IllegalStateException)
    }

    @Test
    fun unexpected_registration_exception_is_bounded_and_redacted() {
        val request = request()

        val result = ProtectedModelDekProvisioningCoordinator.coordinate(
            request = request,
            provision = {
                ProtectedModelDekProvisioningResult.Provisioned(
                    ProtectedModelDekProvisioningEvidence(
                        model = request.model,
                        dek = request.dek,
                        material = ProtectedModelDekMaterial(ByteArray(32) { 9 })
                    )
                )
            },
            registration = ProtectedModelDekRegistrationPort {
                throw IllegalStateException("private registration failure")
            }
        )

        val failed = assertIs<ProtectedModelDekProvisionAndRegisterResult.Failed>(result)
        assertTrue(failed.throwable is IllegalStateException)
        assertEquals(
            "Failed(reason=null, storeReason=null, throwable=java.lang.IllegalStateException)",
            failed.toString()
        )
    }

    @Test
    fun provider_failure_rendering_does_not_expose_private_message() {
        val failed = ProtectedModelDekProvisioningResult.Failed(
            ProtectedModelDekProvisioningFailure.PROVIDER_FAILED,
            IllegalStateException("private provider failure")
        )

        assertEquals(
            "Failed(reason=PROVIDER_FAILED, throwable=java.lang.IllegalStateException)",
            failed.toString()
        )
    }

    private fun request(): ProtectedModelDekProvisioningRequest =
        ProtectedModelDekProvisioningRequest(
            model = ProtectedModelReference(
                ProtectedModelPackageId("package"),
                ProtectedModelGeneration(4)
            ),
            dek = ModelDekReference(
                ModelDekId("dek"),
                ModelDekGeneration(7)
            )
        )
}
