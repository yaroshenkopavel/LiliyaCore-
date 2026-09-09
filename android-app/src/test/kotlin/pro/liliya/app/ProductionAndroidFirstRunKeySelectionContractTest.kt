package pro.liliya.app

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.After
import org.junit.Test
import pro.liliya.android.runtime.AndroidProductRuntimeFirstRunKeyChoice
import pro.liliya.android.runtime.AndroidProductRuntimeFirstRunKeySecurity

class ProductionAndroidFirstRunKeySelectionContractTest {
    @After
    fun cleanup() {
        ProductionAndroidFirstRunKeySelection.clearForTests()
    }

    @Test
    fun explicit_create_once_choice_is_owned_once() {
        val choice = AndroidProductRuntimeFirstRunKeyChoice.CreateOnce(
            dekId = "first-run-dek",
            protectorId = "first-run-protector",
            protectorGeneration = 1L,
            security = AndroidProductRuntimeFirstRunKeySecurity.STRONGBOX
        )

        val first = ProductionAndroidFirstRunKeySelection.select(choice)
        val second = ProductionAndroidFirstRunKeySelection.select(
            AndroidProductRuntimeFirstRunKeyChoice.CreateOnce(
                dekId = "other-dek",
                protectorId = "other-protector",
                protectorGeneration = 1L,
                security = AndroidProductRuntimeFirstRunKeySecurity.SOFTWARE
            )
        )

        assertIs<ProductionAndroidFirstRunKeySelectionResult.Selected>(first)
        assertIs<ProductionAndroidFirstRunKeySelectionResult.AlreadySelected>(second)
        assertEquals(choice, ProductionAndroidFirstRunKeySelection.current())
    }

    @Test
    fun invalid_restore_exact_choice_is_rejected_without_ownership() {
        val result = ProductionAndroidFirstRunKeySelection.select(
            AndroidProductRuntimeFirstRunKeyChoice.RestoreExact(
                dekId = "",
                dekGeneration = 1L
            )
        )

        assertIs<ProductionAndroidFirstRunKeySelectionResult.Rejected>(result)
        assertNull(ProductionAndroidFirstRunKeySelection.current())
    }
}
