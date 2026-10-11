package pro.liliya.core.asf

/**
 * In-process atomic population reservations shared by parallel wave runners.
 * A lease reserves every slot before dispatch; close releases exactly once.
 * The owner must supply the live baseline and use ONE ledger for all runners.
 * This is not a distributed or durable reservation service.
 */
class AgentParallelPopulationLedger(
    private val maxActive: Int,
    private val maxPerRoot: Int
) {
    init {
        require(maxActive > 0 && maxPerRoot > 0)
    }

    private var active = 0
    private val perRoot = mutableMapOf<AgentRootTaskId, Int>()

    @Synchronized
    fun reserve(root: AgentRootTaskId, count: Int): Lease? {
        require(count > 0)
        val nextActive = active.toLong() + count
        val nextRoot = (perRoot[root] ?: 0).toLong() + count
        if (nextActive > maxActive || nextRoot > maxPerRoot) return null
        active = nextActive.toInt()
        perRoot[root] = nextRoot.toInt()
        return Lease(root, count)
    }

    inner class Lease internal constructor(
        private val root: AgentRootTaskId,
        private val count: Int
    ) : AutoCloseable {
        private var released = false

        override fun close() {
            synchronized(this@AgentParallelPopulationLedger) {
                if (released) return
                released = true
                active -= count
                val remaining = (perRoot[root] ?: error("missing root reservation")) - count
                if (remaining == 0) perRoot.remove(root) else perRoot[root] = remaining
            }
        }
    }
}
