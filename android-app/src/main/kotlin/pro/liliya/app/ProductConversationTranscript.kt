package pro.liliya.app

internal class ProductConversationTranscript {
    private val entries = mutableListOf<Entry>()

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
}
