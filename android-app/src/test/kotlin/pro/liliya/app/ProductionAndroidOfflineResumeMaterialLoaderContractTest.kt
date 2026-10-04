package pro.liliya.app

import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import sun.misc.Unsafe

class ProductionAndroidOfflineResumeMaterialLoaderContractTest {
    @Test
    fun missing_license_stops_before_any_other_durable_owner() {
        var deploymentCalls = 0
        var metadataCalls = 0
        var modelCalls = 0

        val result = ProductionAndroidOfflineResumeMaterialLoader.load(
            licensePort = ProductionAndroidOfflineResumeLicenseLoadPort {
                ProductionAndroidActivatedLicenseLoadResult.Missing
            },
            deploymentPort = ProductionAndroidOfflineResumeDeploymentLoadPort {
                deploymentCalls += 1
                error("must not load deployment after missing license")
            },
            metadataPort = ProductionAndroidOfflineResumeMetadataLoadPort {
                metadataCalls += 1
                error("must not load metadata after missing license")
            },
            localModelPort = ProductionAndroidOfflineResumeLocalModelPort {
                modelCalls += 1
                error("must not load model after missing license")
            }
        )

        assertEquals(
            ProductionAndroidOfflineResumeMaterialFailure.LICENSE_MISSING,
            assertIs<ProductionAndroidOfflineResumeMaterialLoadResult.Rejected>(result).reason
        )
        assertEquals(0, deploymentCalls)
        assertEquals(0, metadataCalls)
        assertEquals(0, modelCalls)
    }

    @Test
    fun rejected_license_stops_before_any_other_durable_owner() {
        val result = ProductionAndroidOfflineResumeMaterialLoader.load(
            licensePort = ProductionAndroidOfflineResumeLicenseLoadPort {
                ProductionAndroidActivatedLicenseLoadResult.Rejected
            },
            deploymentPort = ProductionAndroidOfflineResumeDeploymentLoadPort {
                error("must not load deployment after rejected license")
            },
            metadataPort = ProductionAndroidOfflineResumeMetadataLoadPort {
                error("must not load metadata after rejected license")
            },
            localModelPort = ProductionAndroidOfflineResumeLocalModelPort {
                error("must not load model after rejected license")
            }
        )

        assertEquals(
            ProductionAndroidOfflineResumeMaterialFailure.LICENSE_REJECTED,
            assertIs<ProductionAndroidOfflineResumeMaterialLoadResult.Rejected>(result).reason
        )
    }

    @Test
    fun missing_deployment_profile_stops_before_resume_metadata_and_model() {
        val envelope = allocateWithoutConstructor<pro.liliya.core.license.LicenseSignedEnvelope>()

        val result = ProductionAndroidOfflineResumeMaterialLoader.load(
            licensePort = ProductionAndroidOfflineResumeLicenseLoadPort {
                ProductionAndroidActivatedLicenseLoadResult.Loaded(envelope)
            },
            deploymentPort = ProductionAndroidOfflineResumeDeploymentLoadPort {
                ProductionAndroidOfflineDeploymentProfileLoadResult.Missing
            },
            metadataPort = ProductionAndroidOfflineResumeMetadataLoadPort {
                error("must not load metadata after missing deployment profile")
            },
            localModelPort = ProductionAndroidOfflineResumeLocalModelPort {
                error("must not load model after missing deployment profile")
            }
        )

        assertEquals(
            ProductionAndroidOfflineResumeMaterialFailure.DEPLOYMENT_PROFILE_MISSING,
            assertIs<ProductionAndroidOfflineResumeMaterialLoadResult.Rejected>(result).reason
        )
    }

    @Test
    fun missing_resume_metadata_stops_before_model() {
        val envelope = allocateWithoutConstructor<pro.liliya.core.license.LicenseSignedEnvelope>()
        val deployment = allocateWithoutConstructor<ProductionAndroidOfflineDeploymentProfile>()

        val result = ProductionAndroidOfflineResumeMaterialLoader.load(
            licensePort = ProductionAndroidOfflineResumeLicenseLoadPort {
                ProductionAndroidActivatedLicenseLoadResult.Loaded(envelope)
            },
            deploymentPort = ProductionAndroidOfflineResumeDeploymentLoadPort {
                ProductionAndroidOfflineDeploymentProfileLoadResult.Loaded(deployment)
            },
            metadataPort = ProductionAndroidOfflineResumeMetadataLoadPort {
                ProductionAndroidOfflineResumeMetadataLoadResult.Missing
            },
            localModelPort = ProductionAndroidOfflineResumeLocalModelPort {
                error("must not load model after missing resume metadata")
            }
        )

        assertEquals(
            ProductionAndroidOfflineResumeMaterialFailure.RESUME_METADATA_MISSING,
            assertIs<ProductionAndroidOfflineResumeMaterialLoadResult.Rejected>(result).reason
        )
    }

    private inline fun <reified T> allocateWithoutConstructor(): T {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return (field.get(null) as Unsafe).allocateInstance(T::class.java) as T
    }
}
