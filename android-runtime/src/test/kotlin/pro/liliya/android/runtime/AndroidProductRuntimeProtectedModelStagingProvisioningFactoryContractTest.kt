package pro.liliya.android.runtime

import kotlin.test.assertSame
import org.junit.Test
import pro.liliya.core.protectedmodel.LargeProtectedModelOpaqueArtifactId
import pro.liliya.core.protectedmodel.LargeProtectedModelPackageBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingAppendBackendResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingAttemptReference
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingBackend
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingBackendId
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingCoordinator
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingDeleteResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingPrepareResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingSealResult
import pro.liliya.core.protectedmodel.LargeProtectedModelWorkingArtifactHandle
import pro.liliya.core.protectedmodel.ProtectedModelDekResolver
import pro.liliya.core.protectedmodel.ProtectedModelSignerResolver

class AndroidProductRuntimeProtectedModelStagingProvisioningFactoryContractTest {
    @Test
    fun exact_existing_staging_coordinator_is_reused_without_second_owner() {
        val coordinator = LargeProtectedModelStagingCoordinator(
            backend = RejectingBackend(),
            budgets = LargeProtectedModelStagingBudgets(
                maxTotalPlaintextBytes = 1_000_000,
                maxSegmentPlaintextBytes = 100_000,
                maxSegmentCount = 32,
                maxActiveAttempts = 1,
                maxOpaqueIdentifierChars = 128
            )
        )

        val result = AndroidProductRuntimeProtectedModelStagingProvisioningFactory.create(
            stagingCoordinator = coordinator,
            signerResolver = ProtectedModelSignerResolver { _, _ -> null },
            packageBudgets = LargeProtectedModelPackageBudgets(
                maxModelProfileIdChars = 128,
                maxSignerIdChars = 128,
                maxCanonicalSignedManifestBytes = 1_000_000
            ),
            dekResolver = ProtectedModelDekResolver { _, _ -> null }
        )

        assertSame(coordinator, result.stagingCoordinator)
    }

    private class RejectingBackend : LargeProtectedModelStagingBackend {
        override val backendId =
            LargeProtectedModelStagingBackendId("staging-wiring-contract")

        override fun prepare(
            attempt: LargeProtectedModelStagingAttemptReference,
            expectedPlaintextBytes: Long
        ): LargeProtectedModelStagingPrepareResult =
            LargeProtectedModelStagingPrepareResult.Rejected()

        override fun append(
            handle: LargeProtectedModelWorkingArtifactHandle,
            segmentIndex: Int,
            plaintext: ByteArray
        ): LargeProtectedModelStagingAppendBackendResult =
            LargeProtectedModelStagingAppendBackendResult.Rejected()

        override fun seal(
            handle: LargeProtectedModelWorkingArtifactHandle
        ): LargeProtectedModelStagingSealResult =
            LargeProtectedModelStagingSealResult.Rejected()

        override fun delete(
            artifactId: LargeProtectedModelOpaqueArtifactId
        ): LargeProtectedModelStagingDeleteResult =
            LargeProtectedModelStagingDeleteResult.Rejected()
    }
}
