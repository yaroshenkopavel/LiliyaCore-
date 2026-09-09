package pro.liliya.android.runtime

import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test

class AndroidProductRuntimeProtectedModelBudgetProfileContractTest {
    @Test
    fun exact_product_limits_are_preserved() {
        val result = AndroidProductRuntimeProtectedModelBudgetProfile.create(
            AndroidProductRuntimeProtectedModelBudgetInput(
                maxTotalPlaintextBytes = 1_000_000,
                maxTotalCiphertextBodyBytes = 1_000_000,
                maxTotalProtectedPayloadBytes = 1_100_000,
                maxSegmentCount = 64,
                minNonFinalSegmentPlaintextBytes = 4_096,
                maxSegmentPlaintextBytes = 65_536,
                maxSegmentCiphertextBodyBytes = 65_536,
                maxStructuralIdentifierChars = 256,
                maxCanonicalManifestBytes = 1_000_000,
                maxModelProfileIdChars = 128,
                maxSignerIdChars = 128,
                maxCanonicalSignedManifestBytes = 1_100_000,
                maxContainerBytes = 1_200_000,
                maxSignatureBytes = 4_096
            )
        )

        val ready = assertIs<AndroidProductRuntimeProtectedModelBudgetResult.Ready>(result)
        assertEquals(1_000_000, ready.budgets.manifest.maxTotalPlaintextBytes)
        assertEquals(1_000_000, ready.budgets.manifest.maxTotalCiphertextBodyBytes)
        assertEquals(1_100_000, ready.budgets.manifest.maxTotalProtectedPayloadBytes)
        assertEquals(64, ready.budgets.manifest.maxSegmentCount)
        assertEquals(4_096, ready.budgets.manifest.minNonFinalSegmentPlaintextBytes)
        assertEquals(65_536, ready.budgets.manifest.maxSegmentPlaintextBytes)
        assertEquals(65_536, ready.budgets.manifest.maxSegmentCiphertextBodyBytes)
        assertEquals(256, ready.budgets.manifest.maxStructuralIdentifierChars)
        assertEquals(1_000_000, ready.budgets.manifest.maxCanonicalManifestBytes)
        assertEquals(128, ready.budgets.packageEnvelope.maxModelProfileIdChars)
        assertEquals(128, ready.budgets.packageEnvelope.maxSignerIdChars)
        assertEquals(
            1_100_000,
            ready.budgets.packageEnvelope.maxCanonicalSignedManifestBytes
        )
        assertEquals(1_200_000, ready.budgets.container.maxContainerBytes)
        assertEquals(4_096, ready.budgets.container.maxSignatureBytes)
    }

    @Test
    fun invalid_cross_budget_relationship_is_rejected() {
        val result = AndroidProductRuntimeProtectedModelBudgetProfile.create(
            AndroidProductRuntimeProtectedModelBudgetInput(
                maxTotalPlaintextBytes = 1_000,
                maxTotalCiphertextBodyBytes = 1_000,
                maxTotalProtectedPayloadBytes = 900,
                maxSegmentCount = 1,
                minNonFinalSegmentPlaintextBytes = 1,
                maxSegmentPlaintextBytes = 1_000,
                maxSegmentCiphertextBodyBytes = 1_000,
                maxStructuralIdentifierChars = 10,
                maxCanonicalManifestBytes = 1_000,
                maxModelProfileIdChars = 10,
                maxSignerIdChars = 10,
                maxCanonicalSignedManifestBytes = 1_000,
                maxContainerBytes = 2_000,
                maxSignatureBytes = 128
            )
        )

        assertIs<AndroidProductRuntimeProtectedModelBudgetResult.Rejected>(result)
    }

    @Test
    fun zero_or_negative_limit_is_rejected_without_substitution() {
        val result = AndroidProductRuntimeProtectedModelBudgetProfile.create(
            AndroidProductRuntimeProtectedModelBudgetInput(
                maxTotalPlaintextBytes = 0,
                maxTotalCiphertextBodyBytes = 1,
                maxTotalProtectedPayloadBytes = 1,
                maxSegmentCount = 1,
                minNonFinalSegmentPlaintextBytes = 1,
                maxSegmentPlaintextBytes = 1,
                maxSegmentCiphertextBodyBytes = 1,
                maxStructuralIdentifierChars = 1,
                maxCanonicalManifestBytes = 1,
                maxModelProfileIdChars = 1,
                maxSignerIdChars = 1,
                maxCanonicalSignedManifestBytes = 1,
                maxContainerBytes = 1,
                maxSignatureBytes = 1
            )
        )

        assertIs<AndroidProductRuntimeProtectedModelBudgetResult.Rejected>(result)
    }
}
