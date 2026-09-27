package pro.liliya.app

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

internal interface ProductionAndroidConversationSessionPort {
    fun currentSessionId(): String?
    fun freshSessionId(): String
    fun commitSessionId(sessionId: String): Boolean
}

internal interface ProductionAndroidConversationSessionStorage {
    fun readSessionId(): String?
    fun writeSessionId(sessionId: String): Boolean
}

internal fun interface ProductionAndroidConversationSessionIdSource {
    fun next(): String
}

internal class ProductionAndroidConversationSessionOwner(
    private val storage: ProductionAndroidConversationSessionStorage,
    private val ids: ProductionAndroidConversationSessionIdSource
) : ProductionAndroidConversationSessionPort {
    @Synchronized
    override fun currentSessionId(): String? {
        val existing = try {
            storage.readSessionId()
        } catch (_: Exception) {
            return null
        }
        if (existing != null) {
            return if (isValidSessionId(existing)) existing else null
        }

        val fresh = freshSessionId()
        if (!isValidSessionId(fresh)) return null
        return try {
            if (storage.writeSessionId(fresh)) fresh else null
        } catch (_: Exception) {
            null
        }
    }

    override fun freshSessionId(): String = ids.next()

    @Synchronized
    override fun commitSessionId(sessionId: String): Boolean {
        if (!isValidSessionId(sessionId)) return false
        return try {
            storage.writeSessionId(sessionId)
        } catch (_: Exception) {
            false
        }
    }

    private fun isValidSessionId(sessionId: String): Boolean =
        try {
            UUID.fromString(sessionId)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
}

/**
 * App-private pointer to the currently selected durable conversation session.
 *
 * This stores only one opaque session id. Conversation content remains in the encrypted cognitive
 * store owned by Product Runtime.
 */
internal object ProductionAndroidConversationSessionState :
    ProductionAndroidConversationSessionPort {
    @Volatile
    private var owner: ProductionAndroidConversationSessionPort? = null

    fun initialize(context: Context) {
        val preferences = context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
        owner = ProductionAndroidConversationSessionOwner(
            storage = SharedPreferencesSessionStorage(preferences),
            ids = ProductionAndroidConversationSessionIdSource {
                UUID.randomUUID().toString()
            }
        )
    }

    override fun currentSessionId(): String? = owner?.currentSessionId()

    override fun freshSessionId(): String = owner?.freshSessionId().orEmpty()

    override fun commitSessionId(sessionId: String): Boolean =
        owner?.commitSessionId(sessionId) == true

    @Synchronized
    internal fun clearForTests() {
        owner = null
    }

    private class SharedPreferencesSessionStorage(
        private val preferences: SharedPreferences
    ) : ProductionAndroidConversationSessionStorage {
        override fun readSessionId(): String? =
            preferences.getString(CURRENT_SESSION_ID_KEY, null)

        override fun writeSessionId(sessionId: String): Boolean =
            preferences.edit().putString(CURRENT_SESSION_ID_KEY, sessionId).commit()
    }

    private const val PREFERENCES_NAME = "liliya-conversation-session-v1"
    private const val CURRENT_SESSION_ID_KEY = "current-session-id"
}
