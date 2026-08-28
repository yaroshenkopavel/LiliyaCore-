package pro.liliya.core.execution

import pro.liliya.core.logging.LogContext

fun interface ExecutionExecutor {
    fun execute(request: ExecutionRequest, context: LogContext): ExecutionResult
}
