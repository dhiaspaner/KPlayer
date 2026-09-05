# `:state-machine` — a graph-based FSM DSL

A small finite state machine for Kotlin Multiplatform, with no dependencies beyond coroutines. It
drives `PlaybackStatus` transitions in `:video`, but it knows nothing about media and works on its
own.

Package: `com.dhiachemingui.statemachine`. Targets: Android, JVM, iOS.

The idea: **states are nodes, events are edges.** A transition that isn't in the graph can't
happen, so illegal transitions are absent by construction rather than guarded by an `else ->` you
have to remember to write.

---

## Minimal usage

```kotlin
sealed interface MyState : State {
    data object Idle : MyState
    data object Running : MyState
}

sealed interface MyEvent : Event {
    data object Start : MyEvent
    data object Stop : MyEvent
}

val machine = graph {
    initialState(MyState.Idle)

    state(MyState.Idle) {
        on(MyEvent.Start) { transitionTo(MyState.Running) }
    }
    state(MyState.Running) {
        on(MyEvent.Stop) { transitionTo(MyState.Idle) }
    }
}

machine.start()                       // suspend
machine.consume(MyEvent.Start)        // suspend
machine.observe<MyState>().collect { … }
```

`State` and `Event` are **empty marker interfaces** — implement them on your own domain types, so
the machine never forces a shape on your model.

## Concepts

| | |
|---|---|
| `Graph` | the machine: nodes, edges, and the current `MachineState`. Built by `graph { }`. |
| `MachineState` | runtime position — `Inactive`, `Dwelling(node)` (sitting in a state), `Traversing(edge)` (mid-transition). |
| `Node` | one `State`, with `onEnter`/`onExit` visitors, an optional `Decision`, and its `edgeTriggers` map. |
| `Edge` | a `from → to` pair with an `action` and enter/exit visitors. |
| `Decision` | lets a node fire a follow-up event on entry — compound transitions with no caller involvement. |
| `ActionResult` | receiver for edge actions: `fail()` aborts the transition (stays in the source state), `failAndExit()` aborts *and* runs the edge's `onExit`. |

## Dispatch is a hash lookup

`on<T> { }` registers the edge under the event's `KClass` in `Node.edgeTriggers`, so `consume(event)`
is an O(1) map lookup on the current node — not a `when` chain that grows with the state count.

```kotlin
state(PlaybackStatus.Buffering) {
    on<PlaybackEvent.Ready> { transitionTo(PlaybackStatus.Ready) }
    on<PlaybackEvent.Failure> { transitionTo(PlaybackStatus.Error) }
}
```

Both forms key on type: `on<T> { }` is reified and matches any instance of `T` (what you want for
events carrying data, like `Ready(durationMs)`); `on(event) { }` keys on `event::class` and is
convenient for `data object` events.

## Builder API

### `graph { }`

| | |
|---|---|
| `initialState(state)` | where `start()` begins |
| `state(id) { … }` | declare a node |
| `dispatcher(d)` | `CoroutineDispatcher` edge actions run on (default `Dispatchers.Default`) |

### Inside `state(id) { }`

| | |
|---|---|
| `on<T> { … }` / `on(event) { … }` | event-triggered edge |
| `onTransitionTo(state) { … }` | an edge with no event trigger |
| `allows(vararg states)` | plain reachable states |
| `onEnter { state, trigger -> }` | runs on entry; **`trigger` is the event that caused it** |
| `onExit { state, trigger -> }` | runs on exit |
| `decision { state, trigger -> event? }` | auto-fire a follow-up event on entry |

### Inside an edge block

| | |
|---|---|
| `transitionTo(state) { … }` | the destination, plus an optional action |
| `execute { … }` | the action alone |
| `onEnter { (from, to) -> }` / `onExit { … }` | edge visitors |

## Driving and observing

```kotlin
suspend fun start(startingState: MachineState = initialState): Graph
suspend fun consume(event: Event)                                        // primary entry point
suspend fun transitionTo(state: State, trigger: Event? = null): State?   // bypasses edge triggers

fun observeState(): Flow<State>                  // dwells only
inline fun <reified T : State> observe(): Flow<T>
fun observeStateChanges(): Flow<MachineState>    // dwells and traversals
```

`consume` is a no-op unless the machine is `Dwelling` on a node with an edge for that event type —
an unknown event is ignored, not an error.

`transitionTo` is the escape hatch for transitions valid from *anywhere*. `:video` uses it for
`Failure` and `ReleaseRequested`, which would otherwise need an edge on every node.

## Transition order

Worth knowing exactly, because it determines where your side effects belong:

```
from.onExit → edge.onEnter → edge.action (on the graph's dispatcher)
    ├── success → notify Traversing → edge.onExit → currentState = Dwelling(to)
    │             → notify Dwelling → to.onEnter → to.decision → consume(follow-up)
    └── fail()  → [failAndExit(): edge.onExit] → currentState = Dwelling(from)
                  → from.onEnter → notify Dwelling
```

State updates belong in `onEnter`, which receives the triggering event — that is how `:video`
writes `positionMs`, `durationMs` and `errorMessage` out of the event that caused the transition.

## Choosing a dispatcher

If every edge action is trivially non-blocking, `Dispatchers.Unconfined` makes
`scope.launch { consume(event) }` run **synchronously in the caller's thread**, which keeps the
surrounding API synchronous without blocking. That is exactly what `VideoPlayerStateMachine` does,
and it is only valid because its edge actions do nothing but `StateFlow.update`. Anything heavier
belongs on a real dispatcher.

## Tests

```bash
./gradlew :state-machine:jvmTest
```

## Note

This module has no publishing configuration, even though `:core` api-exposes it — see the root
README's *Known gaps*.
