package pro.liliya.core.logging

import java.util.UUID

fun interface CorrelationIdGenerator {
    fun nextId(): String
}

object UuidCorrelationIdGenerator : CorrelationIdGenerator {
    override fun nextId(): String = UUID.randomUUID().toString()
}
