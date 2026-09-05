# `:state-machine` — Module Guide & Trade-off Analysis

Reference doc for the `:state-machine` module: what it is, how it works, and where its
edges are. Read this before extending the DSL or before deciding whether a new problem
in kplayer should be modelled as a graph at all.

Package: `com.dhiachemingui.statemachine` · 371 lines of Kotlin · sole dependency:
`kotlinx-coroutines-core` · targets: Android, JVM, iOS (x64 / arm64 / simulatorArm64),
wasmJs.

> The `:state-machine` row in `CLAUDE.md` lists *Linux* as a target. `build.gradle.kts`
> declares no Linux target; it declares `wasmJs`. The table is stale.

---

## 1. The idea in one sentence

**States are nodes, events are edges.** A transition that is not declared in the graph
cannot happen, so illegal transitions are absent *by construction* rather than guarded
by an `else -> error(...)` branch somebody has to remember to write.

That single design choice is where every pro and almost every con in this doc comes from.

---

## 2. The type vocabulary

| Type | File | What it is |
|---|---|---|
| `State` | `State.kt` | Empty marker interface. Your domain type implements it. |
| `Event` | `Event.kt` | Empty marker interface. Same. |
| `Graph` | `Graph.kt` | The machine: `nodes`, `edges`, `currentState`, `dispatcher`. Built by `graph { }`. |
| `Node` | `Graph.kt` | One `State` + `onEnter` / `onExit` visitors + optional `Decision` + `edgeTriggers: Map<KClass<out Event>, Edge>`. |
| `Edge` | `Graph.kt` | A `from → to` node pair + `action` + `onEnter` / `onExit` visitors. |
| `MachineState` | `MachineState.kt` | Runtime position: `Inactive`, `Dwelling(node)`, `Traversing(edge)`. |
| `Decision` | `Decision.kt` | `fun interface (State, Event?) -> Event?` — a node auto-fires a follow-up event on entry. |
| `ActionResult` | `ActionResult.kt` | Receiver for edge actions. `fail()` aborts; `failAndExit()` aborts *and* runs the edge's `onExit`. |
| `StateVisitor` / `EdgeVisitor` | | `fun interface`s for the entry/exit callbacks. |

`State` and `Event` being *empty* markers is deliberate: the machine never imposes a
shape on your model. `PlaybackStatus` is a plain enum that happens to implement `State`.

---

## 3. Minimal usage

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
    dispatcher(Dispatchers.Unconfined)
    initialState(MyState.Idle)

    state(MyState.Idle) {
        on(MyEvent.Start) { transitionTo(MyState.Running) }
    }
    state(MyState.Running) {
        on(MyEvent.Stop) { transitionTo(MyState.Idle) }
    }
}

machine.start()                        // suspend
machine.consume(MyEvent.Start)         // suspend
machine.observe<MyState>().collect { … }
```

### Builder surface

`graph { }` — `initialState(state)`, `state(id) { … }`, `dispatcher(d)`
(default `Dispatchers.Default`).

Inside `state(id) { }` — `on<T> { … }` / `on(event) { … }`, `onTransitionTo(state) { … }`
(edge with no event trigger), `allows(vararg states)` (plain reachable states),
`onEnter { state, trigger -> }`, `onExit { state, trigger -> }`,
`decision { state, trigger -> event? }`.

Inside an edge block — `transitionTo(state) { … }`, `execute { … }`,
`onEnter { (from, to) -> }`, `onExit { … }`.

### Driving and observing

```kotlin
fun start(startingState: MachineState = initialState): Graph          // not suspend
suspend fun consume(event: Event)
suspend fun transitionTo(state: State, trigger: Event? = null): State?

fun observeState(): Flow<State>                  // dwells only
inline fun <reified T : State> observe(): Flow<T>
fun observeStateChanges(): Flow<MachineState>    // dwells and traversals
```

---

## 4. How dispatch works

`on<T> { }` registers the edge in `Node.edgeTriggers` keyed by `T::class`.
`consume(event)` is then:

```kotlin
if (currentState is Dwelling) {
    findNode(state.id)?.edgeTriggers?.get(event::class)?.let { moveViaEdge(it, event) }
}
```

One hash lookup on the current node — **not** a `when` chain that grows with the state
count. Both registration forms key on type: `on<T> { }` is reified and matches any
instance of `T` (what you want for events carrying data, like `Ready(durationMs)`);
`on(event) { }` keys on `event::class` and reads better for `data object` events.

Note `findNode` is a linear `nodes.find { it.id == id }`. Node count is small
(kplayer has 8), so this is not hot, but it is O(n) per event, not O(1).

---

## 5. Transition order

Worth memorising, because it determines where side effects belong:

```
from.onExit → edge.onEnter → edge.action  (on the graph's dispatcher)
    ├── success → notify Traversing → edge.onExit → currentState = Dwelling(to)
    │             → notify Dwelling → to.onEnter → to.decision → consume(follow-up)
    └── fail()  → [failAndExit(): edge.onExit] → currentState = Dwelling(from)
                  → from.onEnter → notify Dwelling
```

The important detail: **`onEnter` receives the event that caused the transition.**
That is how `PlaybackStateMachine` writes `durationMs` out of `PlaybackEvent.Ready`,
`error` out of `PlaybackEvent.Failure`, and `source` out of `PlaybackEvent.LoadRequested`
— the state reducer and the transition table are the same declaration.

---

## 6. How kplayer uses it

`core/src/commonMain/kotlin/kplayer/player/PlaybackStateMachine.kt` builds one graph with
a node per `PlaybackStatus` and drives a `MutableStateFlow<S : PlayerState<S>>` from each
node's `onEnter`. Both `:audio` and `:video` run that exact graph (ADR 0001).

Three patterns there are worth copying:

1. **Bypass the graph for status-neutral events.** `PositionSynced`, `SpeedChanged`,
   `VolumeChanged` and `SubtitleCueChanged` update the `StateFlow` directly and never
   touch the machine. Position syncs fire several times a second; routing them through
   edges would be pure overhead for a transition that always lands back on the same node.
2. **Use `transitionTo` for globally-valid transitions.** `Failure` and `ReleaseRequested`
   are valid from *any* state; declaring them as an edge on all eight nodes would be
   sixteen lines of noise, so they call `machine.transitionTo(status, event)` instead.
3. **Mirror into a `StateFlow`.** The graph's own observers are `SharedFlow`s with no
   replay (see con #3), so kplayer keeps the authoritative snapshot in its own
   `MutableStateFlow` and never collects `observeState()`.

### The `Unconfined` contract

`PlaybackStateMachine` passes `Dispatchers.Unconfined` for both the coroutine scope and
the graph's action dispatcher, so `scope.launch { machine.consume(event) }` runs
**synchronously in the caller's thread**. That is what lets `onEvent(...)` be a plain
non-suspend function whose effect is visible in `state.value` on the very next line —
which the platform backends rely on.

It is only safe because every edge action here is a non-blocking `StateFlow.update`.
Anything heavier belongs on a real dispatcher, and then callers must stop reading
`state.value` synchronously after dispatching.

---

## 7. Pros

**1. Illegal transitions are unrepresentable.**
The main event. `Paused` has no `PlaybackCompleted` edge unless you declare one, so the
"can't happen" cases don't need runtime guards. Compare with a `when (status)` reducer,
where every new state silently inherits whatever the `else ->` branch does.

**2. The transition table is readable as a table.**
`PlaybackStateMachine.buildGraph()` is the spec. You can review the whole playback
protocol by reading ~120 lines of `state { on<X> { transitionTo(Y) } }` — no control
flow to simulate in your head.

**3. Dispatch is a hash lookup, and stays one.**
`edgeTriggers[event::class]` does not degrade as the machine grows. A hand-written
reducer is a nested `when` whose cost and cognitive load are O(states × events).

**4. Zero coupling to the domain.**
`State` and `Event` are empty interfaces. No base class, no sealed hierarchy
requirement, no generic parameters to thread through. A Kotlin `enum` can be a node.

**5. `onEnter(state, trigger)` unifies transition and reduction.**
Because the entry hook sees the triggering event, the "move to `Ready`" rule and the
"copy `durationMs` off the event" rule live in the same block. In a split
FSM-plus-reducer design those two drift apart.

**6. Genuinely tiny and dependency-free.**
371 lines, only `kotlinx-coroutines-core`. Full KMP reach including wasmJs. No
annotation processor, no codegen, no reflection beyond `KClass` keys. You can read the
entire implementation in fifteen minutes — which matters more than it sounds, because
you *will* need to (see con #1).

**7. Flow-native observation.**
`observeState()` / `observeStateChanges()` compose directly with Compose and StateFlow.
`Traversing` is observable separately from `Dwelling`, so "mid-transition" is a state
the UI can render if it wants to.

**8. Injectable dispatcher.**
The `Unconfined` trick in §6 is only possible because the dispatcher is a builder
parameter. This is what keeps kplayer's public API synchronous without blocking.

**9. `Decision` gives compound transitions for free.**
A node can fire its own follow-up event on entry, so `A → B → C` chains happen without
the caller knowing `B` exists.

**10. `ActionResult.fail()` lets an action veto its own transition.**
Useful when the decision to move depends on work the action itself does.

---

## 8. Cons

Ordered roughly by how likely they are to bite.

**1. Zero tests.**
`state-machine/src` contains exactly one source set: `commonMain`. There is no
`commonTest` directory, no `jvmTest` directory, no test file anywhere — even though
`build.gradle.kts` declares the test dependencies and `state-machine/README.md` tells
you to run `./gradlew :state-machine:jvmTest`. That command is a no-op. Every behaviour
described in this doc is verified only indirectly, through `:core`'s and `:audio`'s
tests. **This is the single biggest liability in the module** — it is the foundation of
the whole player and nothing pins its semantics down.

**2. Not thread-safe, and nothing says so.**
`Graph.currentState` is a plain `var`. `consume()` reads it, then suspends across
`withContext(dispatcher) { action(...) }`, then writes it. Two concurrent `consume()`
calls interleave and corrupt the machine. There is no mutex, no actor, no
`@ThreadSafe`-style documentation. kplayer is safe only because it pins everything to a
single `Unconfined` caller thread — an invariant enforced nowhere in this module.

**3. Observers have no replay and only one slot of buffer.**
Two consequences. *(a)* `replay = 0`, so a subscriber that starts collecting after
`start()` never learns the current state — it must read `graph.currentState` separately,
and there is no atomic way to do both. *(b)* `extraBufferCapacity = 1` absorbs a single
emission; past that, `notifyStateChange` falls back to a suspending `emit` from *inside*
`moveViaEdge`, so **a slow collector back-pressures the state machine and stalls the
transition mid-flight.** kplayer dodges both by mirroring into its own `StateFlow` and
never collecting the graph's flows — a strong hint that the observation API as shipped
is not usable directly.

**4. `observe<T>()` is an unchecked cast.**
```kotlin
inline fun <reified T : State> observe(): Flow<T> = observeState() as Flow<T>
```
No filtering. If any state in the graph is not a `T`, the collector throws
`ClassCastException` at emission time, far from the declaration that caused it. The
honest implementation is `observeState().filterIsInstance<T>()`, and the fact that
`T` is `reified` means it costs nothing to write.

**5. Guards run *after* exit side effects.**
There is no `guard` / `onlyIf` in the DSL. The only way to reject a transition is
`fail()` inside the edge action — but by then `from.onExit` and `edge.onEnter` have
already run, and the rollback path then calls `from.onEnter` again. So a *rejected*
transition still fires `onExit` and a spurious re-entry `onEnter` on the state you never
left. If your `onEnter`/`onExit` do anything non-idempotent (acquire audio focus, start
a timer, log), `fail()` is a trap.

**6. The builder `!!`s its way through malformed graphs.**
`GraphBuilder` has `allNodes[destination!!]!!`, `allNodes[it]!!` and
`allNodes[sb.id]!!`. Write `on<Foo> { execute { … } }` and forget `transitionTo`, and you
get a bare `NullPointerException` from `StateBuilder.allNodes()` at build time, with no
indication of which state or which event. There is no validation pass and no
"unreachable state" / "no such target" diagnostic — which is a real gap for a library
whose selling point is catching structural mistakes.

**7. `transitionTo(state, trigger)` voids the core guarantee.**
It bypasses `edgeTriggers` entirely: `moveViaEdge` does `edges.find { it == edge } ?: edge`,
so when no declared edge matches it fabricates one with a no-op action and default
visitors. Convenient (kplayer needs it for `Failure`/`Released`), but it means "illegal
transitions are impossible" is true only for `consume()`. Nothing marks the escape hatch
as an escape hatch, and nothing lets you declare *which* global transitions are legal.

**8. Unknown events vanish silently.**
`consume()` is a no-op if the current node has no edge for the event type — no return
value, no exception, no callback, no log. Debugging "why didn't my player leave
`Buffering`?" means adding print statements to the library. An
`observeUnhandled(): Flow<Event>` or a `Boolean` return would cost almost nothing.

**9. `Decision` recursion is unbounded.**
A node's `decision` calls `consume(...)` from inside `moveViaEdge`. Two nodes whose
decisions point at each other loop until the stack dies. No depth limit, no cycle
detection.

**10. Dead API in the builder.**
`GraphBuilder.onTransition { }` and `GraphBuilder.onState { }` append to `transitions`
and `stateChanges`, and `build()` never reads either list. They are public, they compile,
they do nothing.

**11. Public mutable internals.**
`Graph.currentState` and `Graph.initialState` are public `var`s; `Node.edgeTriggers` is a
public `MutableMap`; `Node.onEnter` / `onExit` / `decision` and `Edge.action` are public
`var`s. Anyone holding a `Graph` can rewire it after `build()`. The `internal` marker on
`nodes`/`edges` stops nothing outside the module from mutating a node it can reach.

**12. Flat states only.**
No hierarchical/nested states, no orthogonal regions, no history states, no built-in
timers or delayed transitions. `Buffering` cannot be a substate of "has media loaded",
so shared behaviour is copy-pasted across nodes — visible in `PlaybackStateMachine`,
where four terminal statuses share one `forEach` loop precisely to avoid that.

**13. `moveDirectly` has a no-op wrapper.**
`CoroutineScope(dispatcher).run { notifyStateChange(currentState) }` — `run` is not
`launch`; the scope is allocated and discarded and the call runs on the caller's context
regardless. Cosmetic, but it reads as if it does something.

**14. Not published.**
`:core` `api`-exposes `:state-machine`, but the module has no publishing configuration,
so a consumer of a published `:core` cannot resolve `State`, `Event` or `Graph`. Tracked
in the root README's *Known gaps*.

---

## Recently fixed

**`start()` no longer suspends.** It used to be `suspend fun start(...)`, which meant a
caller could not put the machine into its initial state from a constructor —
`PlaybackStateMachine`'s `init` block called it and `:core` did not compile. `start()`
does no suspending work beyond notifying observers, so the fix was to drop the modifier
and give the observers one slot of buffer, letting the start-time notification go out
through `tryEmit` (`Graph.kt`, `TransitionEngine.doStart`). A papercut that happened to
be blocking, not a design problem.

---

## 9. When to reach for it (and when not to)

**Use the graph when** the set of states is small and named, the legal transitions are a
fact about the domain worth writing down, and the events arrive from something you don't
control (a native player, a socket, a hardware callback). That is exactly playback
status, and the module earns its place there.

**Don't use it when** the "state" is really a bag of independent fields. Volume, speed,
position and subtitle cue are all status-neutral in kplayer, and all four correctly
bypass the machine. Modelling them as nodes would multiply the graph without buying a
single invariant.

---

## 10. If you extend this module, do these first

In priority order, based on §8:

1. **Add `commonTest`.** Transition order, `fail()` vs `failAndExit()`, `Decision`
   chains, unknown-event no-op, `transitionTo` on an undeclared edge. Nothing else on
   this list is safe to do without it.
2. **`observeState().filterIsInstance<T>()`** in `observe<T>()`.
3. **Raise the observer buffer** past one slot (`BufferOverflow.DROP_OLDEST`, or expose a
   `StateFlow`) so a slow collector cannot stall a transition.
4. **Validate at `build()`** — replace the `!!`s with messages naming the state and event.
5. **Delete `onTransition` / `onState`,** or wire them up.

---

## See also

- `state-machine/README.md` — the module's own quick reference.
- `docs/adr/0001-sharing-player-logic-between-audio-and-video.md` — why one graph serves
  both backends.
- `core/src/commonMain/kotlin/kplayer/player/PlaybackStateMachine.kt` — the only
  non-trivial consumer, and the best worked example.
