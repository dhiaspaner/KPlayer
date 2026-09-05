# Interface vs Abstract Class — Design Notes

Reference doc for the `VideoPlayer` / `AbstractVideoPlayer` pattern used in this lib.
Revisit this whenever you're deciding how to split a contract between an `interface` and an `abstract class`.

---

## The core mental model

- **Interface = a promise about behavior.** "What you can do."
- **Class = a promise about behavior *and* implementation.** "What you can do, plus how, plus what state you carry."

Everything below falls out of that one sentence.

---

## Decision framework

Ask these questions, in order, when splitting a type between interface and abstract class:

1. **Does it need to hold state?**
   Interfaces can't have backing fields or private mutable state. If you need a private field (like a state machine instance), you need a class.

2. **Does it need a constructor?**
   Interfaces have zero constructors. Any construction-time configuration (buffer sizes, dependencies, defaults) forces a class.

3. **Do you need multiple inheritance of *type*?**
   A class extends one class but implements many interfaces. If a type needs to be several unrelated things at once, interfaces are the only option.

4. **Do you need to share implementation across unrelated hierarchies?**
   If two unrelated types need the same logic but share no "is-a" relationship, an interface default method (or plain composition) fits better than forcing a shared superclass.

5. **Do you want `protected` access?**
   Interfaces are effectively all-public. If subclasses need shared internals hidden from external callers, that's class-only.

6. **Do you want to enforce invariants centrally?**
   If you want a *guarantee* that every implementation behaves consistently (not just a suggestion), encode that behavior once in a base class.

---

## Comparison table

| | Interface | Abstract Class |
|---|---|---|
| Multiple inheritance | ✅ yes | ❌ single only |
| Constructors / config at creation | ❌ no | ✅ yes |
| Private / protected state | ❌ no | ✅ yes |
| Enforced shared logic | ⚠️ only via default methods, fragile | ✅ strong, centralized |
| Flexibility for unrelated types | ✅ high | ❌ low (forces "is-a") |
| Represents | *capability* | *identity + capability* |

### The "is-a" vs "can-do" litmus test
- **"A `VideoPlayer` *is a* thing with this state machine and this feedback pipeline"** → abstract class (identity + shared machinery)
- **"A `VideoPlayer` *can do* load/play/pause"** → interface (pure capability)

### Quick gut-check
> Do I need to **give** something (state, constructor, protected access) to every implementer and **guarantee** they use it correctly? → abstract class.
> Do I just need to **ask** something of every implementer? → interface.

---

## Why this lib uses interface + abstract class together

```kotlin
interface VideoPlayer { ... }                          // the contract
abstract class AbstractVideoPlayer : VideoPlayer { ... } // the shared engine
```

This is the idiomatic pattern: define the **capability** as an interface, then provide an **optional shared skeleton** as an abstract class that most implementers will want.

Concretely, in `AbstractVideoPlayer`:

- **Encapsulated state** — `private val machine = VideoPlayerStateMachine()` and `feedbackEvents` (a `MutableSharedFlow`) are private/protected fields. Interfaces structurally cannot hold this; only a class can.
- **Constructor configuration** — `feedbackBufferCapacity: Int = 16` customizes construction. Interfaces have no constructors, so this alone rules out doing it purely at the interface level.
- **Enforced consistency** — every concrete player (ExoPlayer-backed, AVPlayer-backed, a test fake, etc.) is *forced* to route all actions through the same `VideoPlayerStateMachine` via `onEvent()`. There's no way for a subclass to "forget" to wire up state correctly or diverge in behavior — this is the Template Method pattern: the base class defines *what* happens (action → state machine), subclasses define *how* (`execute(action)` calls the real player SDK).
- **Narrow subclass contract** — subclasses implement exactly one method (`execute`), instead of reimplementing all six `VideoPlayer` methods plus flow wiring each time.
- **`protected` boundary** — `onEvent` and `feedbackEvents` are visible to subclasses but hidden from external callers. Interfaces can't express this; everything on an interface is effectively public.
- **Escape hatch preserved** — because `VideoPlayer` is still a separate interface, you can implement it directly (e.g. a lightweight `videoplayer.FakeVideoPlayer` for tests) without inheriting the state machine at all. The abstract class is an option, not a requirement.

### Composition alternative (and why inheritance won here)

The same sharing could be done via composition instead of inheritance:

```kotlin
class ExoVideoPlayer : VideoPlayer {
    private val machine = PlaybackStateMachine()  // held, not inherited
    override val state = machine.state
    override fun play() = machine.onEvent(...)
}
```

This works, but each implementer wires the machine manually and *could* get it wrong or diverge over time. Inheritance via `AbstractVideoPlayer` **structurally guarantees** the wiring — you cannot forget to hook it up, because the base class does it once, centrally, in a private field no subclass can bypass.

**Rule of thumb going forward:** when you want to guarantee a shared skeleton across many implementations → template-method-style inheritance beats composition. When you just want to reuse a chunk of logic without forcing a rigid skeleton → composition wins.

---

## Applying this elsewhere in the lib

When adding a new component, run through the framework:

1. Does it need private state or constructor config? → needs a class somewhere.
2. Does the "capability" side need to support multiple/unrelated implementations (including lightweight fakes for tests)? → keep a separate interface.
3. Do you want to *guarantee* consistent behavior across implementers, not just suggest it? → put that behavior in an abstract base class, expose only the narrow abstract method(s) implementers must fill in.
4. If in doubt, default to interface + abstract base class combo — it's flexible, testable, and enforces invariants without locking out alternative implementations.