package pro.liliya.app

import android.content.Context
import java.io.File
import pro.liliya.android.runtime.AndroidProductRuntimeObservability

/**
 * Android application owner for the runtime observability root.
 *
 * The app owns only app-private filesystem placement. Core event types and serialization stay
 * behind the android-runtime boundary.
 */
class ProductionAndroidAppObservability private constructor(
    internal val runtime: AndroidProductRuntimeObservability
) {
    fun installLoggerWriter() {
        runtime.installLoggerWriter()
    }

    companion object {
        private const val DIRECTORY = "observability"

        fun create(
            context: Context
        ): ProductionAndroidAppObservability =
            ProductionAndroidAppObservability(
                AndroidProductRuntimeObservability.create(
                    File(context.applicationContext.filesDir, DIRECTORY)
                )
            )
    }
}
