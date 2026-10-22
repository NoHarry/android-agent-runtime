package cc.noharry.agent.androiduse

/**
 * Live nodes of the latest read of each display, by handle. Handles never repeat across reads, so a handle
 * resolves only while the read that issued it is still its display's latest: reading display 1 leaves display 0's
 * elements usable, and reading display 0 again retires its old handles instead of pointing them at other nodes.
 */
internal class LiveNodeTables<T> {
    private val tables = HashMap<Int, Map<Int, T>>()
    private var lastHandle = 0

    /** A handle no earlier read has issued. */
    @Synchronized
    fun nextHandle(): Int = ++lastHandle

    /** Makes one read's [nodes] the table of [displayId]; that display's earlier handles stop resolving. */
    @Synchronized
    fun publish(displayId: Int, nodes: Map<Int, T>) {
        tables[displayId] = HashMap(nodes)
    }

    /** The node behind [handle], or null once the read that issued it is no longer its display's latest. */
    @Synchronized
    fun resolve(handle: Int): T? = tables.values.firstNotNullOfOrNull { it[handle] }
}

/** [handle] names no node of its display's latest read: the observation behind the action is stale. */
internal class StaleNodeException(handle: Int) :
    IllegalStateException("Element handle $handle is not from the latest read of its display.")
