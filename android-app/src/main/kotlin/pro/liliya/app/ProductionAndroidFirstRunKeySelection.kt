package pro.liliya.app

import pro.liliya.android.runtime.AndroidProductRuntimeFirstRunKeyChoice
import pro.liliya.android.runtime.AndroidProductRuntimeFirstRunKeyChoiceFactory
import pro.liliya.android.runtime.AndroidProductRuntimeFirstRunKeyChoiceResult

sealed interface ProductionAndroidFirstRunKeySelectionResult {
    data class Selected(
        val choice: AndroidProductRuntimeFirstRunKeyChoice
    ) : ProductionAndroidFirstRunKeySelectionResult

    data object AlreadySelected : ProductionAndroidFirstRunKeySelectionResult

    data object Rejected : ProductionAndroidFirstRunKeySelectionResult
}

/**
 * Process-local ownership of one explicit first-run key choice.
 *
 * Key Selection != Key Discovery.
 * Key Selection != Rotation.
 * Key Selection != Security-Level Defaulting.
 */
object ProductionAndroidFirstRunKeySelection {
    @Volatile
    private var selected: AndroidProductRuntimeFirstRunKeyChoice? = null

    @Synchronized
    fun select(
        choice: AndroidProductRuntimeFirstRunKeyChoice
    ): ProductionAndroidFirstRunKeySelectionResult {
        if (selected != null) {
            return ProductionAndroidFirstRunKeySelectionResult.AlreadySelected
        }

        return when (AndroidProductRuntimeFirstRunKeyChoiceFactory.create(choice)) {
            is AndroidProductRuntimeFirstRunKeyChoiceResult.Ready -> {
                selected = choice
                ProductionAndroidFirstRunKeySelectionResult.Selected(choice)
            }
            AndroidProductRuntimeFirstRunKeyChoiceResult.Rejected ->
                ProductionAndroidFirstRunKeySelectionResult.Rejected
        }
    }

    internal fun current(): AndroidProductRuntimeFirstRunKeyChoice? = selected

    @Synchronized
    internal fun clearForTests() {
        selected = null
    }
}
