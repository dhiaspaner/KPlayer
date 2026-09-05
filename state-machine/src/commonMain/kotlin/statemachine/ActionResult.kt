package com.dhiachemingui.statemachine

/**
 * An edge's action. **Not** a suspending function: it runs inline, on the caller's
 * thread, inside the graph's transition lock, between the source state being left and
 * the target state being committed. Anything that needs to suspend belongs outside the
 * graph — launch it from an `onEnter` hook once the transition has landed.
 */
typealias EdgeAction = ActionResult.(Event?) -> Unit

interface ActionResult {
    fun fail()
    fun failAndExit()
}
