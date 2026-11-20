# Portal Watch

Watches newly loaded chunks for nether portals. When one appears it rings a bell, prints the
coordinates and has Baritone walk you to a spot 3 blocks short of it, ringing again on arrival.
Baritone commands go out as `#` chat on the next client tick; Baritone cancels them at
`ClientPacketListener.sendChat`, so nothing reaches the server. Sent from inside a client command
they were ignored. One-shot: it turns itself off after each find.

| Command | Behavior |
| --- | --- |
| `/portalwatch` | Status |
| `/portalwatch on` | Watch; portals already loaded are ignored |
| `/portalwatch spawn` | Watch and run `#goto 0 0` |
| `/portalwatch off` | Stop watching |

Standing on the nether roof (Y ≥ 128) it only reports roof portals. Every reported portal is
remembered for the session, and a dimension change turns it off.

Build with `gradle build` (Loom 1.19); the jar lands in `build/libs/`. Tested on JAH 2026-11-20 (0.1.0): detection and the stop worked; `spawn` didn't send its goto, fixed in 0.2.0.
