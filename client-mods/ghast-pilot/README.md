# Ghast Pilot

Autopilot for riding a happy ghast, in the spirit of Ghast Autopilot (which stops at 1.21.11). Client-only and
input-level: it holds forward and jump and turns your view, and the ghast flies where you look, exactly as if you
were flying it.

| Control | Behavior |
| --- | --- |
| **O** (rebindable under Ghast Pilot) | Toggle: to the destination if one is set, otherwise forward mode |
| `/ghastpilot forward` | Fly the way you face; turn to steer. It handles height and terrain |
| `/ghastpilot goto <x> <z>` | Fly there at the height you're at now |
| `/ghastpilot goto <x> <y> <z>` | Fly there at height y |
| `/ghastpilot clear` | Drop the destination so the key flies forward |
| `/ghastpilot off` | Stop |
| `/ghastpilot` | Status |

It turns at most 6° a tick and turns in place when more than 30° off course, so it can't orbit the target. It climbs
when terrain within 10 blocks ahead would hit the ghast's body, then settles back to the cruise height once the way
down is clear; holding jump raises that height. It stops on arrival (within 2 blocks, with a bell), when you press back
or when you dismount.

Portal Watch drives it through `/ghastpilot steer <x> <z>` (a goto without the chat line) whenever you're riding a
happy ghast; see `../portal-watch`.

Build with `gradle build` (Loom 1.19); the jar lands in `build/libs/`.
