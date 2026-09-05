package kplayer.core.player

import com.dhiachemingui.statemachine.Graph
import com.dhiachemingui.statemachine.MachineState
import com.dhiachemingui.statemachine.graph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kplayer.core.event.PlaybackEvent
import kplayer.core.state.PlaybackStatus
import kplayer.core.state.PlayerState

/**
 * Drives a [PlayerState] from the [PlaybackEvent]s a native engine reports.
 *
 * One graph node per [PlaybackStatus]; each [PlaybackEvent] subtype is dispatched
 * by type (`KClass` key), so dispatch is a hash lookup rather than a `when` chain,
 * and an illegal transition is simply absent from the graph.
 *
 * Shared by every backend — audio and video both run this exact graph. The two
 * hooks below are the only places a backend may differ, which is what keeps the
 * shared version honest: anything a hook cannot express does not belong here.
 *
 * [onEvent] is synchronous, and takes no dispatcher to be so: it applies the event on
 * the calling thread, under the graph's re-entrant transition lock, and returns only
 * once the event — and any [com.dhiachemingui.statemachine.Decision] chain it sets
 * off — has been committed to [state]. A platform callback may fire an event and read
 * `state.value` on the very next line.
 *
 * @param reduceCustom applies status-neutral events the shared vocabulary does not
 *   model, returning `null` to leave the state alone. Today that is only
 *   [PlaybackEvent.SubtitleCueChanged] — video maps it onto `activeSubtitle`, and
 *   audio, having no surface to draw on, absorbs it.
 * @param onLoad applied to the state built when a new source starts loading, for
 *   fields only a specific backend has. Video clears `activeSubtitle` here, so the
 *   outgoing media's last cue does not sit on screen until the new one produces
 *   its first.
 */
class PlaybackStateMachine<S : PlayerState<S>>(
    initialState: S,
    private val reduceCustom: (S, PlaybackEvent) -> S? = { _, _ -> null },
    private val onLoad: (S) -> S = { it },
) {
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<S> = _state.asStateFlow()

    private val machine: Graph = buildGraph()

    init {
        machine.start(MachineState.Dwelling(initialState.status))
    }

    /**
     * Applies [event] and returns with [state] already updated.
     *
     * The whole `when` runs inside the graph's transition lock, so the fast paths below
     * — which write [_state] without moving the machine — cannot land in the middle of
     * another thread's transition. The lock is re-entrant, so the `machine.consume` /
     * `machine.transitionTo` calls in the slow paths simply re-acquire it.
     */
    fun onEvent(event: PlaybackEvent): Unit = machine.withTransitionLock {
        when (event) {
            // High-frequency positional update: no status change, skip the graph.
            //
            // Only where a position means something. Idle, Stopped, Error and
            // Released have no position, and Buffering/Ready have one that was
            // just reset by a load — a sync tick already in flight when the load
            // landed would otherwise put the outgoing media's position back.
            // Completed is in the list because seeking away from the end is how
            // every replay starts: drop it there and the seek bar stays pinned at
            // the end while the media plays from the beginning.
            is PlaybackEvent.PositionSynced -> _state.update { prev ->
                when (prev.status) {
                    PlaybackStatus.Playing,
                    PlaybackStatus.Paused,
                    PlaybackStatus.Completed -> prev.copyBase(positionMs = event.positionMs)

                    else -> prev
                }
            }

            is PlaybackEvent.SpeedChanged ->
                _state.update { it.copyBase(playbackSpeed = event.speed) }

            is PlaybackEvent.VolumeChanged ->
                _state.update { it.copyBase(volume = event.volume) }

            // Also high-frequency and status-neutral: cues change several times a
            // minute and must never move the player through the graph.
            is PlaybackEvent.SubtitleCueChanged ->
                _state.update { reduceCustom(it, event) ?: it }

            // Global transitions: valid from any state, bypass the per-state edge map.
            is PlaybackEvent.Failure -> machine.transitionTo(PlaybackStatus.Error, event)

            is PlaybackEvent.ReleaseRequested ->
                machine.transitionTo(PlaybackStatus.Released, event)

            else -> machine.consume(event)
        }
        Unit
    }

    private fun buildGraph(): Graph = graph {
        initialState(PlaybackStatus.Idle)

        // ── Terminal / inactive states: only a new Load restarts the pipeline ──
        listOf(
            PlaybackStatus.Idle,
            PlaybackStatus.Stopped,
            PlaybackStatus.Error,
            PlaybackStatus.Released,
        ).forEach { status ->
            state(id = status) {
                onEnter { enteredStatus, trigger ->
                    _state.update { prev ->
                        when (enteredStatus) {
                            PlaybackStatus.Stopped ->
                                prev.copyBase(status = PlaybackStatus.Stopped, positionMs = 0L)

                            PlaybackStatus.Error ->
                                prev.copyBase(
                                    status = PlaybackStatus.Error,
                                    error = (trigger as? PlaybackEvent.Failure)?.error
                                )

                            else -> prev.copyBase(status = enteredStatus as PlaybackStatus)
                        }
                    }
                }
                on<PlaybackEvent.LoadRequested> { transitionTo(PlaybackStatus.Buffering) }
            }
        }

        // ── Buffering ─────────────────────────────────────────────────────────
        state(id = PlaybackStatus.Buffering) {
            onEnter { _, trigger ->
                _state.update { prev ->
                    when (trigger) {
                        is PlaybackEvent.LoadRequested -> onLoad(
                            prev.copyBase(
                                status = PlaybackStatus.Buffering,
                                source = trigger.source,
                                positionMs = 0L,
                                durationMs = 0L,
                                error = null,
                            )
                        )

                        else -> prev.copyBase(status = PlaybackStatus.Buffering)
                    }
                }
            }
            on<PlaybackEvent.Ready> { transitionTo(PlaybackStatus.Ready) }
            on<PlaybackEvent.StopRequested> { transitionTo(PlaybackStatus.Stopped) }
            on<PlaybackEvent.Stopped> { transitionTo(PlaybackStatus.Stopped) }
        }

        // ── Ready ─────────────────────────────────────────────────────────────
        state(id = PlaybackStatus.Ready) {
            onEnter { _, trigger ->
                _state.update { prev ->
                    prev.copyBase(
                        status = PlaybackStatus.Ready,
                        durationMs = (trigger as? PlaybackEvent.Ready)?.durationMs ?: prev.durationMs
                    )
                }
            }
            on<PlaybackEvent.PlaybackStarted> { transitionTo(PlaybackStatus.Playing) }
            on<PlaybackEvent.PlaybackPaused> { transitionTo(PlaybackStatus.Paused) }
            on<PlaybackEvent.LoadRequested> { transitionTo(PlaybackStatus.Buffering) }
            on<PlaybackEvent.StopRequested> { transitionTo(PlaybackStatus.Stopped) }
            on<PlaybackEvent.Stopped> { transitionTo(PlaybackStatus.Stopped) }
        }

        // ── Playing ───────────────────────────────────────────────────────────
        state(id = PlaybackStatus.Playing) {
            onEnter { _, _ ->
                _state.update { prev ->
                    prev.copyBase(status = PlaybackStatus.Playing, error = null)
                }
            }
            on<PlaybackEvent.PlaybackPaused> { transitionTo(PlaybackStatus.Paused) }
            on<PlaybackEvent.BufferingStarted> { transitionTo(PlaybackStatus.Buffering) }
            on<PlaybackEvent.SeekToStarted> { transitionTo(PlaybackStatus.Buffering) }
            on<PlaybackEvent.PlaybackCompleted> { transitionTo(PlaybackStatus.Completed) }
            on<PlaybackEvent.StopRequested> { transitionTo(PlaybackStatus.Stopped) }
            on<PlaybackEvent.Stopped> { transitionTo(PlaybackStatus.Stopped) }
        }

        // ── Paused ────────────────────────────────────────────────────────────
        state(id = PlaybackStatus.Paused) {
            onEnter { _, _ -> _state.update { it.copyBase(status = PlaybackStatus.Paused) } }
            on<PlaybackEvent.PlaybackStarted> { transitionTo(PlaybackStatus.Playing) }
            // End-of-media: native players flip to "not playing" (ExoPlayer
            // isPlaying=false, AVPlayer rate=0) and *then* signal completion. If
            // the pause lands first we're in Paused when PlaybackCompleted arrives,
            // so it must be accepted here too — a genuinely paused stream never
            // completes, so this only ever fires at the true end of playback.
            on<PlaybackEvent.PlaybackCompleted> { transitionTo(PlaybackStatus.Completed) }
            on<PlaybackEvent.LoadRequested> { transitionTo(PlaybackStatus.Buffering) }
            on<PlaybackEvent.StopRequested> { transitionTo(PlaybackStatus.Stopped) }
            on<PlaybackEvent.Stopped> { transitionTo(PlaybackStatus.Stopped) }
        }

        // ── Completed ─────────────────────────────────────────────────────────
        // Reached the end, but *not* a dead end: replaying the same item is a
        // seek and a play, not a reload, on every engine we drive. Grouped with
        // the terminal states it accepted only LoadRequested, so a restart played
        // for real while the machine sat here — and with the status stuck,
        // `positionMs` stopped updating and the seek bar stayed pinned at the end.
        state(id = PlaybackStatus.Completed) {
            onEnter { _, _ ->
                _state.update { prev ->
                    prev.copyBase(
                        status = PlaybackStatus.Completed,
                        // The last sync tick lands wherever the polling interval
                        // left it, typically just short of the end. The item did
                        // finish, so say so rather than showing 1:39 / 1:40 under
                        // a bar that never quite fills. Live streams (duration 0)
                        // have no end to snap to.
                        positionMs = if (prev.durationMs > 0L) prev.durationMs else prev.positionMs,
                    )
                }
            }
            // The engine reports it started again — from a seek back, or from the
            // implicit rewind a media element does when play() is called at the end.
            on<PlaybackEvent.PlaybackStarted> { transitionTo(PlaybackStatus.Playing) }
            on<PlaybackEvent.BufferingStarted> { transitionTo(PlaybackStatus.Buffering) }
            on<PlaybackEvent.LoadRequested> { transitionTo(PlaybackStatus.Buffering) }
            on<PlaybackEvent.StopRequested> { transitionTo(PlaybackStatus.Stopped) }
            on<PlaybackEvent.Stopped> { transitionTo(PlaybackStatus.Stopped) }
        }
    }
}
