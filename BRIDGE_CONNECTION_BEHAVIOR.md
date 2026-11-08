# Pico connection behavior in Android 0.21.0

Connect records permission to use the selected SCX2 endpoint and pressure mode.
This permission is separate from a live UDP session and contains no controls,
payload queues, stick positions, trigger values or macro playback state.

## Transitions and recovery

Page changes, profile management/loading, editor transitions, connection settings
and Release all invalidate scoped output and cancel local gesture/timer/macro
state. A ready session is retired: its old queue is discarded and STOP is sent
best effort. A new UUID handshake starts after local cleanup, at the validated
connected endpoint. It first sends sequence-1 neutral; only the matching ACK
permits new touches. Rapid page changes during a neutral handshake keep that
handshake's original deadline and leave the latest selected page visible.

An unavailable READY during automatic recovery is retried every 250 ms within
that attempt's original three-second bound. Neutral ACK still has its 750 ms
bound. Failed attempts retry after 0.5, 1, 2, then at most 5 seconds while the
permission remains active and the app is foregrounded. There is no protocol or
trigger-capability downgrade. Existing manual connection failure detection and
Windows/legacy receiver routes remain available.

Explicit Disconnect, Practice or a different explicit output destination cancel
permission, pending retries and old socket callbacks. Unsaved settings do not
change the connected endpoint. Invalid addresses/ports cannot replace it.
Unsupported keyboard/mouse actions on SCX2 cannot disconnect the gamepad route.

## Background and process restart

Backgrounding immediately retires the session and cancels all actions. The first
background callback records wall and monotonic time. Further background callbacks
do not extend it. Returning before 300,000 milliseconds may reconnect, with new
neutral confirmation and new touch required. At or beyond that boundary, Connect
is required. Clock rollback, incompatible monotonic time or malformed saved intent
fails closed.

A separate `bridge_connection_intent` preference file stores the endpoint,
pressure option and presence timestamps. No profile file is changed by this
policy. A one-second foreground heartbeat permits a process killed without a
background callback to reconnect on a quick relaunch; the limit is conservatively
measured from the last recorded heartbeat. No held output is restored.

## Verification and limits

Tests cover strict five-minute boundaries, both clocks, malformed endpoint
records, retry bounds, neutral acknowledgement, stale session/endpoint replies,
old queued presses, actual UDP recovery, page retention, macro cancellation and
real Android activity ownership cleanup. The signed APK updates the existing
community application ID and signing key. Emulator verification does not replace
physical Android, Pico/XAC or Xbox gameplay checks. Firmware and SCX2 wire bytes
are unchanged.
