# Portal Watch

Watches newly loaded chunks for nether portals. When one appears it sends `#stop` to Baritone
(Baritone cancels `#` chat at `ClientPacketListener.sendChat`, so nothing reaches the server),
rings a bell and prints the coordinates. One-shot: it turns itself off after each find.

| Command | Behavior |
| --- | --- |
| `/portalwatch` | Status |
| `/portalwatch on` | Watch; portals already loaded are ignored |
| `/portalwatch spawn` | Watch and run `#goto 0 0` |
| `/portalwatch off` | Stop watching |

Standing on the nether roof (Y ≥ 128) it only reports roof portals. Every reported portal is
remembered for the session, and a dimension change turns it off.

Build with `gradle build` (Loom 1.19); the jar lands in `build/libs/`. Not yet tested in-game.
