package pro.liliya.app

internal data class ProductConversationTranscriptSnapshot(
    val speakers: List<String>,
    val messages: List<String>
)

internal class ProductConversationTranscript private constructor(
    private val entries: MutableList<Entry>
) {
    constructor() : this(mutableListOf())

    fun appendUser(message: String) {
        append(Speaker.USER, message)
    }

    fun appendLiliya(message: String) {
        append(Speaker.LILIYA, message)
    }

    fun ensureLastUser(message: String): Boolean {
        val normalized = message.trim()
        if (normalized.isEmpty()) return false

        val last = entries.lastOrNull()
        if (last != null && last.speaker == Speaker.USER && last.message == normalized) {
            return false
        }

        entries += Entry(Speaker.USER, normalized)
        return true
    }

    fun rollbackLastUser(message: String): Boolean {
        val normalized = message.trim()
        if (normalized.isEmpty()) return false

        val last = entries.lastOrNull() ?: return false
        if (last.speaker != Speaker.USER || last.message != normalized) return false

        entries.removeAt(entries.lastIndex)
        return true
    }

    fun render(): String = entries.joinToString(separator = "\n\n") { entry ->
        "${entry.speaker.label}: ${entry.message}"
    }

    fun isEmpty(): Boolean = entries.isEmpty()

    fun snapshot(): ProductConversationTranscriptSnapshot =
        ProductConversationTranscriptSnapshot(
            speakers = entries.map { it.speaker.name },
            messages = entries.map { it.message }
        )

    fun snapshotWithinBudget(
        maxEntries: Int,
        maxUtf8Bytes: Int
    ): ProductConversationTranscriptSnapshot =
        snapshotWithinBudget(
            sourceEntries = entries,
            maxEntries = maxEntries,
            maxUtf8Bytes = maxUtf8Bytes
        )

    fun snapshotWithinBudgetExcludingLastUser(
        message: String,
        maxEntries: Int,
        maxUtf8Bytes: Int
    ): ProductConversationTranscriptSnapshot {
        val normalized = message.trim()
        val last = entries.lastOrNull()
        val sourceEntries =
            if (
                normalized.isNotEmpty() &&
                last != null &&
                last.speaker == Speaker.USER &&
                last.message == normalized
            ) {
                entries.dropLast(1)
            } else {
                entries
            }

        return snapshotWithinBudget(
            sourceEntries = sourceEntries,
            maxEntries = maxEntries,
            maxUtf8Bytes = maxUtf8Bytes
        )
    }

    private fun snapshotWithinBudget(
        sourceEntries: List<Entry>,
        maxEntries: Int,
        maxUtf8Bytes: Int
    ): ProductConversationTranscriptSnapshot {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(maxUtf8Bytes > 0) { "maxUtf8Bytes must be positive" }

        val selected = mutableListOf<Entry>()
        var usedUtf8Bytes = 0

        for (entry in sourceEntries.asReversed()) {
            if (selected.size >= maxEntries) break

            val entryUtf8Bytes =
                entry.speaker.name.toByteArray(Charsets.UTF_8).size +
                    entry.message.toByteArray(Charsets.UTF_8).size
            if (entryUtf8Bytes > maxUtf8Bytes - usedUtf8Bytes) break

            selected += entry
            usedUtf8Bytes += entryUtf8Bytes
        }

        selected.reverse()
        return ProductConversationTranscriptSnapshot(
            speakers = selected.map { it.speaker.name },
            messages = selected.map { it.message }
        )
    }

    private fun append(speaker: Speaker, message: String) {
        val normalized = message.trim()
        if (normalized.isEmpty()) return
        entries += Entry(speaker, normalized)
    }

    private data class Entry(
        val speaker: Speaker,
        val message: String
    )

    private enum class Speaker(val label: String) {
        USER("Вы"),
        LILIYA("Лилия")
    }

    companion object {
        fun restore(snapshot: ProductConversationTranscriptSnapshot): ProductConversationTranscript {
            if (snapshot.speakers.size != snapshot.messages.size) {
                return ProductConversationTranscript()
            }

            val restored = mutableListOf<Entry>()
            for (index in snapshot.speakers.indices) {
                val speaker = Speaker.entries.firstOrNull { it.name == snapshot.speakers[index] }
                    ?: return ProductConversationTranscript()
                val message = snapshot.messages[index].trim()
                if (message.isEmpty()) continue
                restored += Entry(speaker, message)
            }
            return ProductConversationTranscript(restored)
        }
    }
}
