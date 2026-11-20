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
| `/portalwatch explore` | Watch and `#explore` outward from here, holding your current Y (`exploreMaintainY`) |
| `/portalwatch resume` | Restart the last trip a find interrupted (same explore origin) |
| `/portalwatch off` | Stop watching |

Standing on the nether roof (Y ≥ 128) it only reports roof portals. Every reported portal is
remembered for the session, and a dimension change turns it off.

Build with `gradle build` (Loom 1.19); the jar lands in `build/libs/`. Tested on JAH 2026-11-20: 0.2.0 heads to spawn, finds roof portals, walks up to them and rings on arrival.
