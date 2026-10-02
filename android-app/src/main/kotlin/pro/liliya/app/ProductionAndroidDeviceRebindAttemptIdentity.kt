package pro.liliya.app

import android.content.Context
import java.security.SecureRandom
import java.util.Base64

internal object ProductionAndroidDeviceRebindAttemptIdentity {
    private const val PREFERENCES = "liliya-device-rebind-v1"
    private const val KEY = "attempt-id"

    fun loadOrCreate(context: Context): String {
        val preferences =
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

        preferences.getString(KEY, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        val value = try {
            "device-rebind-attempt-v1:" +
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        } finally {
            bytes.fill(0)
        }

        synchronized(this) {
            preferences.getString(KEY, null)
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }

            check(preferences.edit().putString(KEY, value).commit()) {
                "device rebind attempt identity persistence failed"
            }
        }
        return value
    }
}
