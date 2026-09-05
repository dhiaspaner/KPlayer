package com.dhiachemingui.statemachine

/** Two-node ping-pong. Every event is legal from the node it is dispatched on, so a */
/** run of N consume() calls must produce exactly N transitions and strict alternation. */
sealed interface Parity : State {
    data object Even : Parity
    data object Odd : Parity
}

data object Tick : Event

/** A light with a decision on Yellow, for exercising compound transitions. */
sealed interface Light : State {
    data object Red : Light
    data object Green : Light
    data object Yellow : Light
}

sealed interface Signal : Event {
    data object Go : Signal
    data object Slow : Signal
    data object Halt : Signal
    data object Unknown : Signal
}
