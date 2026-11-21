# Ghast Pilot

Autopilot for riding a happy ghast, in the spirit of Ghast Autopilot (which stops at 1.21.11). Client-only and
input-level: it holds forward and turns your view, and the ghast flies where you look, exactly as if you were flying it.

| Control | Behavior |
| --- | --- |
| **O** (rebindable under Ghast Pilot) | Toggle. With no destination it just holds forward and you steer |
| `/ghastpilot goto <x> <z>` | Set a destination; starts flying if you're mounted |
| `/ghastpilot clear` | Drop the destination so the key cruises again |
| `/ghastpilot off` | Stop |
| `/ghastpilot` | Status |

Flying to a destination it turns at most 6° a tick, holds the height you started at, and climbs when terrain within
10 blocks ahead would hit the ghast's body. It never descends below the cruise height on its own; hold jump to raise it.
It stops on arrival (within 6 blocks, with a bell), when you press back, or when you dismount.

Build with `gradle build` (Loom 1.19); the jar lands in `build/libs/`.
