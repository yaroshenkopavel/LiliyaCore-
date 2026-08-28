package pro.liliya.core.runtime

import java.util.concurrent.atomic.AtomicReference

class RuntimeStateHolder(
    initialState: RuntimeState = RuntimeState.CREATED
) {
    private val state = AtomicReference(initialState)

    fun current(): RuntimeState = state.get()

    internal fun compareAndSet(
        expected: RuntimeState,
        next: RuntimeState
    ): Boolean = state.compareAndSet(expected, next)
}
