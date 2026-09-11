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

    fun render(): String = entries.joinToString(separator = "\n\n") { entry ->
        "${entry.speaker.label}: ${entry.message}"
    }

    fun isEmpty(): Boolean = entries.isEmpty()

    fun snapshot(): ProductConversationTranscriptSnapshot =
        ProductConversationTranscriptSnapshot(
            speakers = entries.map { it.speaker.name },
            messages = entries.map { it.message }
        )

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
