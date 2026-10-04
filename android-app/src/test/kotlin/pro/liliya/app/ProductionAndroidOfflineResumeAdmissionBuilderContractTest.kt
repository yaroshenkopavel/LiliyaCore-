package pro.liliya.app

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import pro.liliya.android.devicekey.AndroidActivationDeviceBinding
import pro.liliya.android.devicekey.AndroidActivationDeviceBindingResult
import pro.liliya.core.license.LicenseDeviceBindingReference
import pro.liliya.core.license.LicenseDeviceBindingReferenceFactory
import pro.liliya.core.license.LicensePolicyContext
import pro.liliya.core.license.LicenseReplaySequence
import pro.liliya.core.license.LicenseRevocationEpoch

class ProductionAndroidOfflineResumeAdmissionBuilderContractTest {
    private val policy = ProductionAndroidOfflineResumeAdmissionPolicy(
        feature = "chat",
        principal = "liliya-product-runtime",
        capability = "model.local",
        authorityScope = "global"
    )
    private val now = Instant.parse("2026-10-04T12:00:00Z")
    private val binding = AndroidActivationDeviceBinding(
        installationId = "installation-v1:test",
        deviceKeyFingerprint = "platform-key:test"
    )
    private val bindingReference = LicenseDeviceBindingReferenceFactory.create(
        installationId = binding.installationId,
        deviceKeyFingerprint = binding.deviceKeyFingerprint
    ).value

    @Test
    fun deployment_product_mismatch_stops_before_device_and_security() {
        val result = ProductionAndroidOfflineResumeAdmissionBuilder.build(
            deploymentProductId = "other-product",
            facts = facts(productId = "liliya-pro"),
            policy = policy,
            now = now,
            deviceBindingPort = ProductionAndroidOfflineResumeDeviceBindingPort {
                error("device binding must not load after product mismatch")
            },
            securityFloorPort = ProductionAndroidOfflineResumeSecurityFloorPort {
                error("security floor must not run after product mismatch")
            }
        )

        assertEquals(
            ProductionAndroidOfflineResumeAdmissionFailure.DEPLOYMENT_PRODUCT_MISMATCH,
            assertIs<ProductionAndroidOfflineResumeAdmissionResult.Rejected>(result).reason
        )
    }

    @Test
    fun signed_device_binding_is_required() {
        val result = ProductionAndroidOfflineResumeAdmissionBuilder.build(
            deploymentProductId = "liliya-pro",
            facts = facts(deviceBindingReference = null),
            policy = policy,
            now = now,
            deviceBindingPort = ProductionAndroidOfflineResumeDeviceBindingPort {
                error("device binding must not load when signed binding is absent")
            },
            securityFloorPort = ProductionAndroidOfflineResumeSecurityFloorPort {
                error("security floor must not run without signed binding")
            }
        )

        assertEquals(
            ProductionAndroidOfflineResumeAdmissionFailure.DEVICE_BINDING_MISSING,
            assertIs<ProductionAndroidOfflineResumeAdmissionResult.Rejected>(result).reason
        )
    }

    @Test
    fun current_device_must_match_signed_binding_before_security_floor() {
        val result = ProductionAndroidOfflineResumeAdmissionBuilder.build(
            deploymentProductId = "liliya-pro",
            facts = facts(deviceBindingReference = "binding-v1:different"),
            policy = policy,
            now = now,
            deviceBindingPort = ProductionAndroidOfflineResumeDeviceBindingPort {
                AndroidActivationDeviceBindingResult.Ready(binding)
            },
            securityFloorPort = ProductionAndroidOfflineResumeSecurityFloorPort {
                error("security floor must not run after binding mismatch")
            }
        )

        assertEquals(
            ProductionAndroidOfflineResumeAdmissionFailure.DEVICE_BINDING_MISMATCH,
            assertIs<ProductionAndroidOfflineResumeAdmissionResult.Rejected>(result).reason
        )
    }

    @Test
    fun durable_security_rejection_stops_before_admission() {
        val result = ProductionAndroidOfflineResumeAdmissionBuilder.build(
            deploymentProductId = "liliya-pro",
            facts = facts(),
            policy = policy,
            now = now,
            deviceBindingPort = ProductionAndroidOfflineResumeDeviceBindingPort {
                AndroidActivationDeviceBindingResult.Ready(binding)
            },
            securityFloorPort = ProductionAndroidOfflineResumeSecurityFloorPort {
                ProductionAndroidLicenseServiceSecuritySyncCoreResult.Rejected(
                    ProductionAndroidLicenseServiceSecuritySyncFailure.DURABLE_STATE_REJECTED
                )
            }
        )

        assertEquals(
            ProductionAndroidOfflineResumeAdmissionFailure.SECURITY_STATE_REJECTED,
            assertIs<ProductionAndroidOfflineResumeAdmissionResult.Rejected>(result).reason
        )
    }

    @Test
    fun effective_durable_floor_is_carried_into_fresh_admission() {
        val effective = LicensePolicyContext(
            now = now.plusSeconds(5),
            minimumRevocationEpoch = LicenseRevocationEpoch(9),
            minimumReplaySequence = LicenseReplaySequence(12),
            suspiciousTimeOrReplayState = true,
            requiredDeviceBindingReference = LicenseDeviceBindingReference(bindingReference)
        )

        val result = ProductionAndroidOfflineResumeAdmissionBuilder.build(
            deploymentProductId = "liliya-pro",
            facts = facts(revocationEpoch = 3, replaySequence = 4),
            policy = policy,
            now = now,
            deviceBindingPort = ProductionAndroidOfflineResumeDeviceBindingPort {
                AndroidActivationDeviceBindingResult.Ready(binding)
            },
            securityFloorPort = ProductionAndroidOfflineResumeSecurityFloorPort {
                ProductionAndroidLicenseServiceSecuritySyncCoreResult.Ready(
                    context = effective,
                    contact = ProductionAndroidLicenseServiceSecuritySyncContact.NOT_CONFIGURED
                )
            }
        )

        val ready = assertIs<ProductionAndroidOfflineResumeAdmissionResult.Ready>(result)
        assertEquals(9, ready.input.minimumRevocationEpoch)
        assertEquals(12, ready.input.minimumReplaySequence)
        assertEquals(true, ready.input.suspiciousTimeOrReplayState)
        assertEquals(bindingReference, ready.input.requiredDeviceBindingReference)
        assertEquals("subject-1", ready.input.subject)
        assertEquals("liliya-pro", ready.input.productId)
        assertEquals(effective, ready.effectivePolicyContext)
    }

    private fun facts(
        productId: String = "liliya-pro",
        revocationEpoch: Long = 3,
        replaySequence: Long? = 4,
        deviceBindingReference: String? = bindingReference
    ) = ProductionAndroidOfflineResumeVerifiedLicenseFacts(
        productId = productId,
        subject = "subject-1",
        revocationEpoch = revocationEpoch,
        replaySequence = replaySequence,
        deviceBindingReference = deviceBindingReference
    )
}
