# Portal Watch

Watches newly loaded chunks for nether portals. When one appears it rings a bell, prints the
coordinates and has Baritone walk you to a spot 3 blocks short of it, ringing again on arrival.
Baritone commands go out as `#` chat on the next client tick; Baritone cancels them at
`ClientPacketListener.sendChat`, so nothing reaches the server. Sent from inside a client command
they were ignored. One-shot: it turns itself off after each find.

| Command | Behavior |
| --- | --- |
| `/portalwatch` | Status |
| `/portalwatch on` | Watch only; move however you like. Portals already loaded are ignored |
| `/portalwatch spawn` | Watch and `#goto 0 0` |
| `/portalwatch goto <x> <z>` | Watch and `#goto x z` |
| `/portalwatch explore` | Watch and sweep a square spiral out from here, lanes one view-width apart (2 × view distance in chunks) |
| `/portalwatch resume` | Continue the last trip: back to where a find pulled you off the sweep, then on round the spiral |
| `/portalwatch off` | Stop watching |

Standing on the nether roof (Y ≥ 128) it only reports roof portals. Every reported portal is
remembered until you change dimension, which turns it off. The last trip survives dimension changes
and reconnects (not a game restart), so resume works after a trip through a portal.

The sweep replaced Baritone's `#explore` in 0.4.0: explore heads for the nearest unloaded chunk,
which loads while you're still a view distance away, so it trails that far inside the edge and each
lap pushed out about one chunk. The sweep sends `#goto` corner to corner instead and never re-walks a
lane, but it doesn't know which land you covered before.

Build with `gradle build` (Loom 1.19); the jar lands in `build/libs/`. Tested on JAH 2026-11-20: 0.2.0 heads to spawn, finds roof portals, walks up to them and rings on arrival.
