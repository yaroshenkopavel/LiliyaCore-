package pro.liliya.core.events

import pro.liliya.core.logging.LogContext

interface CoreEvent {
    val type: String
    val context: LogContext
    val metadata: Map<String, String>
}
