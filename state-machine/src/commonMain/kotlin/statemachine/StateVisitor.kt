package com.dhiachemingui.statemachine

fun interface StateVisitor {
    fun accept(state: State, trigger: Event?)
}
