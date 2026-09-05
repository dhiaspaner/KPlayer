package com.dhiachemingui.statemachine

import com.dhiachemingui.statemachine.MachineState.Inactive
import com.dhiachemingui.statemachine.impl.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.reflect.KClass

fun graph(initBlock: GraphBuilder.() -> Unit): Graph {
    return GraphBuilder().apply(initBlock).build()
}

/**
 * A finite state machine over a graph of [Node]s and [Edge]s.
 *
 * **Transitions are synchronous.** [start], [consume] and [transitionTo] do not suspend:
 * they read the current node, run the edge action inline on the calling thread, and
 * commit the new state before returning. A caller may therefore dispatch an event and
 * read the result on the next line, which is what the media backends rely on.
 *
 * **Transitions are serialized.** Every entry point runs under [withTransitionLock], a
 * re-entrant lock. A second thread dispatching concurrently blocks until the first
 * transition — including any [Decision] chain it sets off — has fully committed, so no
 * two callers can read the same `currentState` and then overwrite each other. The lock
 * is re-entrant precisely because a [Decision] calls back into [consume] from inside the
 * transition that triggered it.
 */
@Suppress("MemberVisibilityCanBePrivate")
class Graph internal constructor(
    var initialState: MachineState = Inactive(),
    var currentState: MachineState = Inactive(),
) {
    val currentStateName: String get() = currentState.id::class.simpleName ?: "Unknown"
    internal val nodes: MutableList<Node> = mutableListOf()
    internal val edges: MutableList<Edge> = mutableListOf()

    /**
     * Serializes transitions. Re-entrant so a [Decision]'s nested [consume] proceeds
     * on the owning thread instead of deadlocking against the transition that raised it.
     */
    internal val guard = ReentrantGuard()

    /**
     * Nesting depth of [Decision]-driven transitions, read and written only under
     * [guard]. Guards against a decision cycle recursing until the stack dies; see
     * [MAX_TRANSITION_DEPTH].
     */
    internal var transitionDepth: Int = 0

    // Outbound notification only — never an event queue, and never the mechanism that
    // serializes transitions (that is `guard`). One slot plus DROP_OLDEST so that
    // emitting from the non-suspending transition path always succeeds, and a collector
    // that falls behind resynchronises on the newest state rather than losing it.
    internal val stateObserver = MutableSharedFlow<State>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    internal val machineStateObserver = MutableSharedFlow<MachineState>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Runs [block] in the same critical section transitions use.
     *
     * Callers that keep state of their own alongside the machine's — a `StateFlow` a
     * node's `onEnter` writes, say — can wrap a whole event in this to make their own
     * updates atomic with the transition, rather than merely adjacent to it. Re-entrant,
     * so calling [consume] inside [block] is fine.
     */
    fun <T> withTransitionLock(block: () -> T): T = guard.withGuard(block)

    inline fun <reified T : State> observe(): Flow<T> {
        @Suppress("UNCHECKED_CAST")
        return observeState() as Flow<T>
    }

    fun observeState(): Flow<State> = stateObserver

    fun observeStateChanges(): Flow<MachineState> = machineStateObserver

    fun start(startingState: MachineState = initialState): Graph = withTransitionLock {
        if (startingState is MachineState.Traversing) {
            throw IllegalArgumentException("Invalid initial state")
        }

        doStart(startingState)

        this
    }

    /**
     * Applies [event] to the current node, if that node declares an edge for its type.
     * An event with no edge is ignored. Returns once the transition — and any
     * [Decision] chain it triggers — has been committed.
     */
    fun consume(event: Event): Unit = withTransitionLock {
        val state = currentState as? MachineState.Dwelling ?: return@withTransitionLock
        val validNode = findNode(state.id)
        validNode?.edgeTriggers?.get(event::class)?.let {
            moveViaEdge(it, event)
        }
        Unit
    }

    /**
     * Moves to [state] regardless of whether an edge was declared for it — the escape
     * hatch for transitions valid from anywhere. Returns `null` if no such node exists.
     */
    fun transitionTo(state: State, trigger: Event? = null): State? = withTransitionLock {
        val validNode = findNode(state) ?: return@withTransitionLock null
        doTransition(validNode, trigger)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Graph) return false

        if (initialState != other.initialState) return false
        if (currentState != other.currentState) return false
        if (nodes.toSet() != other.nodes.toSet()) return false
        if (edges.toSet() != other.edges.toSet()) return false

        return true
    }

    override fun hashCode(): Int {
        var result = initialState.hashCode()
        result = 31 * result + currentState.hashCode()
        result = 31 * result + nodes.hashCode()
        result = 31 * result + edges.hashCode()
        return result
    }

    override fun toString(): String = buildString {
        append(currentState::class.simpleName)
        if (currentState is MachineState.Dwelling) {
            append(" on $currentStateName")
        } else if (currentState is MachineState.Traversing) {
            val state = currentState as MachineState.Traversing
            append(" from ${state.edge.from.id::class.simpleName}")
            append(" -> ${state.edge.to.id::class.simpleName}")
        }
    }
}

class Node(val id: State) {
    val edgeTriggers: MutableMap<KClass<out Event>, Edge> = mutableMapOf()
    var onEnter: StateVisitor = StateVisitor { _, _ -> }
    var onExit: StateVisitor = StateVisitor { _, _ -> }
    var decision: Decision? = null

    override fun equals(other: Any?) = if (other is Node) other.id == id else false

    override fun hashCode(): Int = id.hashCode()
}

data class Edge (val from: Node, val to: Node) {
    constructor(edge: Pair<State, State>) : this(Node(edge.first), Node(edge.second))

    var onEnter: EdgeVisitor = EdgeVisitor { }
    var onExit: EdgeVisitor = EdgeVisitor { }
    var action: EdgeAction = {}
}

fun interface EdgeVisitor {
    fun accept(edge: Pair<State, State>)
}
