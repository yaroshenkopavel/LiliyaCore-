package pro.liliya.app

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import pro.liliya.android.runtime.AndroidProductRuntimeFirstRunProductInputFailure
import pro.liliya.android.runtime.AndroidProductRuntimeStartupAuthorityAssemblyFailure
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseCanonicalPayload
import pro.liliya.core.license.LicenseKeyId
import pro.liliya.core.license.LicenseSignature
import pro.liliya.core.license.LicenseSignedEnvelope
import pro.liliya.core.license.LicenseVersion
import pro.liliya.core.licensetransport.LicenseClientTransportFailure
import pro.liliya.core.licensetransport.LicenseRemoteServiceFailure

class ProductionAndroidFirstRunAcquisitionOrchestratorContractTest {
    @Test
    fun missing_model_fails_before_license_acquisition() {
        var acquisitionCalled = false

        val result = ProductionAndroidFirstRunAcquisitionOrchestrator.prepareAndInstall(
            localModelFile = null,
            licenseAcquisition = ProductionAndroidFirstRunLicenseAcquisitionPort {
                acquisitionCalled = true
                error("must not acquire without selected model")
            },
            signedInstall = ProductionAndroidFirstRunSignedInstallPort { _, _ ->
                error("must not install without selected model")
            }
        )

        assertIs<ProductionAndroidFirstRunAcquisitionResult.LocalModelRequired>(result)
        assertEquals(false, acquisitionCalled)
    }

    @Test
    fun missing_selected_model_file_fails_before_license_acquisition() {
        var acquisitionCalled = false
        val missing = File("build/nonexistent-first-run-model-${System.nanoTime()}")

        val result = ProductionAndroidFirstRunAcquisitionOrchestrator.prepareAndInstall(
            localModelFile = missing,
            licenseAcquisition = ProductionAndroidFirstRunLicenseAcquisitionPort {
                acquisitionCalled = true
                error("must not acquire for missing model")
            },
            signedInstall = ProductionAndroidFirstRunSignedInstallPort { _, _ ->
                error("must not install for missing model")
            }
        )

        assertIs<ProductionAndroidFirstRunAcquisitionResult.LocalModelRequired>(result)
        assertEquals(false, acquisitionCalled)
    }

    @Test
    fun service_rejection_is_preserved_and_install_is_not_called() = withModel { model ->
        var installCalled = false

        val result = ProductionAndroidFirstRunAcquisitionOrchestrator.prepareAndInstall(
            localModelFile = model,
            licenseAcquisition = ProductionAndroidFirstRunLicenseAcquisitionPort {
                ProductionAndroidLicenseEnvelopeAcquisitionResult.ServiceRejected(
                    LicenseRemoteServiceFailure.ENROLLMENT_REQUIRED
                )
            },
            signedInstall = ProductionAndroidFirstRunSignedInstallPort { _, _ ->
                installCalled = true
                error("must not install rejected acquisition")
            }
        )

        val rejected =
            assertIs<ProductionAndroidFirstRunAcquisitionResult.LicenseServiceRejected>(result)
        assertEquals(LicenseRemoteServiceFailure.ENROLLMENT_REQUIRED, rejected.reason)
        assertEquals(false, installCalled)
    }

    @Test
    fun transport_failure_is_preserved_and_install_is_not_called() = withModel { model ->
        var installCalled = false

        val result = ProductionAndroidFirstRunAcquisitionOrchestrator.prepareAndInstall(
            localModelFile = model,
            licenseAcquisition = ProductionAndroidFirstRunLicenseAcquisitionPort {
                ProductionAndroidLicenseEnvelopeAcquisitionResult.Failed(
                    LicenseClientTransportFailure.TLS_FAILURE
                )
            },
            signedInstall = ProductionAndroidFirstRunSignedInstallPort { _, _ ->
                installCalled = true
                error("must not install failed acquisition")
            }
        )

        val failed =
            assertIs<ProductionAndroidFirstRunAcquisitionResult.LicenseAcquisitionFailed>(result)
        assertEquals(LicenseClientTransportFailure.TLS_FAILURE, failed.reason)
        assertEquals(false, installCalled)
    }

    @Test
    fun signed_envelope_and_exact_selected_model_are_forwarded_unchanged() = withModel { model ->
        val envelope = envelope()
        var observedEnvelope: LicenseSignedEnvelope? = null
        var observedModel: File? = null

        val result = ProductionAndroidFirstRunAcquisitionOrchestrator.prepareAndInstall(
            localModelFile = model,
            licenseAcquisition = ProductionAndroidFirstRunLicenseAcquisitionPort {
                ProductionAndroidLicenseEnvelopeAcquisitionResult.Signed(envelope)
            },
            signedInstall = ProductionAndroidFirstRunSignedInstallPort { signed, selected ->
                observedEnvelope = signed
                observedModel = selected
                ProductionAndroidFirstRunProductInstallResult.AlreadyConfigured
            }
        )

        assertEquals(envelope, observedEnvelope)
        assertEquals(model, observedModel)
        assertIs<ProductionAndroidFirstRunAcquisitionResult.AlreadyConfigured>(result)
    }

    @Test
    fun product_input_rejection_is_preserved() = withModel { model ->
        val result = signedInstallResult(
            model,
            ProductionAndroidFirstRunProductInstallResult.ProductInputRejected(
                AndroidProductRuntimeFirstRunProductInputFailure.TRUST_INPUT_REJECTED
            )
        )

        val rejected = assertIs<ProductionAndroidFirstRunAcquisitionResult.ProductInputRejected>(
            result
        )
        assertEquals(
            AndroidProductRuntimeFirstRunProductInputFailure.TRUST_INPUT_REJECTED,
            rejected.reason
        )
    }

    @Test
    fun trust_verification_rejection_is_preserved() = withModel { model ->
        val result = signedInstallResult(
            model,
            ProductionAndroidFirstRunProductInstallResult.TrustVerificationRejected
        )

        assertIs<ProductionAndroidFirstRunAcquisitionResult.TrustVerificationRejected>(result)
    }

    @Test
    fun authority_rejection_is_preserved() = withModel { model ->
        val result = signedInstallResult(
            model,
            ProductionAndroidFirstRunProductInstallResult.AuthorityRejected(
                AndroidProductRuntimeStartupAuthorityAssemblyFailure.DIRECT_GRANT_REJECTED
            )
        )

        val rejected = assertIs<ProductionAndroidFirstRunAcquisitionResult.AuthorityRejected>(result)
        assertEquals(
            AndroidProductRuntimeStartupAuthorityAssemblyFailure.DIRECT_GRANT_REJECTED,
            rejected.reason
        )
    }

    @Test
    fun unexpected_acquisition_or_install_exception_fails_closed() = withModel { model ->
        val acquisitionFailure = ProductionAndroidFirstRunAcquisitionOrchestrator.prepareAndInstall(
            localModelFile = model,
            licenseAcquisition = ProductionAndroidFirstRunLicenseAcquisitionPort {
                error("private acquisition failure")
            },
            signedInstall = ProductionAndroidFirstRunSignedInstallPort { _, _ ->
                error("must not reach install")
            }
        )
        assertIs<ProductionAndroidFirstRunAcquisitionResult.Failed>(acquisitionFailure)

        val installFailure = ProductionAndroidFirstRunAcquisitionOrchestrator.prepareAndInstall(
            localModelFile = model,
            licenseAcquisition = ProductionAndroidFirstRunLicenseAcquisitionPort {
                ProductionAndroidLicenseEnvelopeAcquisitionResult.Signed(envelope())
            },
            signedInstall = ProductionAndroidFirstRunSignedInstallPort { _, _ ->
                error("private install failure")
            }
        )
        assertIs<ProductionAndroidFirstRunAcquisitionResult.Failed>(installFailure)
    }

    private fun signedInstallResult(
        model: File,
        installed: ProductionAndroidFirstRunProductInstallResult
    ): ProductionAndroidFirstRunAcquisitionResult =
        ProductionAndroidFirstRunAcquisitionOrchestrator.prepareAndInstall(
            localModelFile = model,
            licenseAcquisition = ProductionAndroidFirstRunLicenseAcquisitionPort {
                ProductionAndroidLicenseEnvelopeAcquisitionResult.Signed(envelope())
            },
            signedInstall = ProductionAndroidFirstRunSignedInstallPort { _, _ -> installed }
        )

    private fun envelope() = LicenseSignedEnvelope(
        schemaVersion = LicenseVersion(1),
        algorithm = LicenseAlgorithm("ECDSA-P256-SHA256"),
        signingKeyId = LicenseKeyId("first-run-test-key"),
        payload = LicenseCanonicalPayload.of(byteArrayOf(1, 2, 3)),
        signature = LicenseSignature.of(byteArrayOf(4, 5, 6))
    )

    private fun withModel(block: (File) -> Unit) {
        val model = File.createTempFile("liliya-first-run-", ".lpm1")
        try {
            block(model)
        } finally {
            model.delete()
        }
    }
}
