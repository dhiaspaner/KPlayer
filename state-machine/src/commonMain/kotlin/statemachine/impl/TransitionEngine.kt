package com.dhiachemingui.statemachine.impl

import com.dhiachemingui.statemachine.*

/**
 * How deep a [com.dhiachemingui.statemachine.Decision] chain may nest before the graph
 * gives up.
 *
 * A decision fires an event on entry, and that event can land on a node whose own
 * decision fires again. Two nodes pointing their decisions at each other recurse until
 * the stack dies — with no message saying which states were involved, and, on Kotlin/
 * Native, no reliable way to catch it. The limit converts that into an
 * [IllegalStateException] naming the chain. It is not a queue: the chain still runs
 * synchronously, it is only stopped from running forever.
 *
 * Real chains are one or two deep; 100 is far past any legitimate use.
 */
internal const val MAX_TRANSITION_DEPTH = 100

internal fun Graph.doStart(startingState: MachineState) {
    currentState = startingState
    if (currentState is MachineState.Dwelling) {
        val state = currentState as MachineState.Dwelling
        state.node.onEnter.accept(state.node.id, null)
    }

    notifyStateChange(currentState)
}

internal fun Graph.doTransition(node: Node, trigger: Event?): State? = when (currentState) {
    is MachineState.Dwelling ->
        moveViaEdge(Edge((currentState as MachineState.Dwelling).node, node), trigger)

    is MachineState.Inactive -> moveDirectly(node, trigger)
    else -> null
}

/**
 * Runs one transition to completion, inline on the calling thread.
 *
 * The caller already holds `graph.guard` — every public entry point takes it — so
 * `currentState` cannot change underneath this function, and the read-decide-commit
 * sequence below is atomic with respect to other callers.
 */
internal fun Graph.moveViaEdge(edge: Edge, trigger: Event?): State {
    check(transitionDepth < MAX_TRANSITION_DEPTH) {
        "Decision chain exceeded $MAX_TRANSITION_DEPTH nested transitions " +
            "(at ${edge.from.id} -> ${edge.to.id}, trigger $trigger). " +
            "A Decision is almost certainly cycling."
    }

    val registeredEdge = edges.find { it == edge } ?: edge
    registeredEdge.from.onExit.accept(registeredEdge.from.id, trigger)

    val visibleEdge = registeredEdge.from.id to registeredEdge.to.id
    registeredEdge.onEnter.accept(visibleEdge)
    val captor = ActionResultCaptor()

    // Inline, not `withContext(dispatcher)`. Suspending here was what let a second
    // caller enter consume() between this thread reading currentState and writing it.
    registeredEdge.action(captor, trigger)

    if (captor.success) {
        notifyStateChange(
            MachineState.Traversing(
                edge = registeredEdge,
                trigger = trigger
            )
        )
        registeredEdge.onExit.accept(visibleEdge)
        currentState = MachineState.Dwelling(edge.to)
        notifyStateChange(currentState)
        registeredEdge.to.onEnter.accept(registeredEdge.to.id, trigger)

        // A decision re-enters consume() on this thread. `guard` is re-entrant, so the
        // nested transition proceeds and completes before this one returns.
        registeredEdge.to.decision?.decide(registeredEdge.to.id, trigger)?.let {
            transitionDepth++
            try {
                consume(it)
            } finally {
                transitionDepth--
            }
        }
    } else {
        if (captor.andExit) {
            registeredEdge.onExit.accept(visibleEdge)
        }
        currentState = MachineState.Dwelling(edge.from)
        registeredEdge.from.onEnter.accept(edge.from.id, trigger)
        notifyStateChange(currentState)
    }
    return registeredEdge.to.id
}

internal fun Graph.moveDirectly(node: Node, trigger: Event?): State {
    currentState = MachineState.Dwelling(node.id)
    node.onEnter.accept(node.id, trigger)
    notifyStateChange(currentState)
    return node.id
}

internal fun Graph.transitionTo(node: Node, trigger: Event? = null): State? {
    val validNode = findNode(node.id) ?: return null
    return doTransition(validNode, trigger)
}

internal fun Graph.findNode(id: State): Node? = nodes.find { it.id == id }

/**
 * Publishes a state change to the observer flows.
 *
 * `tryEmit`, not `emit`: the transition path does not suspend, and a slow collector
 * must never be able to stall a transition mid-flight. The flows are configured
 * `DROP_OLDEST`, so this always succeeds and a collector that falls behind resumes on
 * the newest state.
 */
private fun Graph.notifyStateChange(state: MachineState) {
    machineStateObserver.tryEmit(state)
    if (state is MachineState.Dwelling) {
        stateObserver.tryEmit(state.id)
    }
}

private class ActionResultCaptor : ActionResult {
    var success = true
    var andExit = false

    override fun fail() {
        success = false
    }

    override fun failAndExit() {
        success = false
        andExit = true
    }
}
