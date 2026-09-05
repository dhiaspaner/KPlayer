package com.dhiachemingui.statemachine

fun interface Decision {
    fun decide(state: State, trigger: Event?): Event?
}
