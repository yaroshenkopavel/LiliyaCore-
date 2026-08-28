package pro.liliya.core.services

import pro.liliya.core.logging.LogContext

interface CoreService {
    val descriptor: ServiceDescriptor

    fun start(context: LogContext)

    fun stop(context: LogContext)
}
