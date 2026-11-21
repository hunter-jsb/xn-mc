# XNMC

Our Fabric client tools in one mod, for Minecraft 26.3 (Fabric Loader 0.20.1, Fabric API 0.165.0+26.3, Java 25).
Part of xn-mc; see the root README for a sparse checkout that fetches only this folder.

Build with `gradle build` (Loom 1.19; it also runs AFK Guard's policy checks), then copy
`build/libs/xnmc-<version>.jar` into `~/.var/app/com.mojang.Minecraft/.minecraft/mods/` with the game closed.

## The menu

**;** (rebindable under XNMC), `/xnmc`, or Mods → XNMC → Configure opens one screen with each feature's status and
buttons. The coordinates box at the top (`x z` or `x y z`) feeds the Go to buttons. Buttons act and close the menu;
replies land in chat. Nothing is added to the title, pause or disconnect screens.

## Portal Watch (`xnmc/portalwatch`)

Watches newly loaded chunks for nether portals. When one appears it rings a bell, prints the coordinates and takes you
to a spot 3 blocks short of it, ringing again on arrival. One-shot: it turns itself off after each find.

| Command | Behavior |
| --- | --- |
| `/portalwatch` | Status |
| `/portalwatch on` | Watch only; move however you like. Portals already loaded are ignored |
| `/portalwatch spawn` | Watch and head to 0 0 |
| `/portalwatch goto <x> <z>` | Watch and head to x z |
| `/portalwatch explore` | Watch and sweep a square spiral out from here, lanes 2 × view distance apart |
| `/portalwatch resume` | Continue the last trip: back to where a find pulled you off the sweep, then on round the spiral |
| `/portalwatch off` | Stop watching |

Each leg is carried by Ghast Pilot when you're riding a happy ghast and by Baritone (`#goto`, sent from the client
tick; Baritone cancels it before it reaches the server) on foot, chosen per leg. Standing on the nether roof (Y ≥ 128)
it only reports roof portals. Reported portals are remembered until you change dimension, which turns it off; the last
trip survives dimension changes and reconnects (not a game restart).

The sweep replaced Baritone's `#explore`, which heads for the nearest unloaded chunk; that chunk loads while you're
still a view distance away, so it trailed that far inside the edge and each lap pushed out about one chunk. The sweep
never re-walks a lane, but doesn't know which land you covered before.

## Ghast Pilot (`xnmc/ghastpilot`)

Autopilot for riding a happy ghast, after Ghast Autopilot (which stops at 1.21.11). Input-level: it holds forward and
jump and turns your view, and the ghast flies where you look, as if you were flying it.

| Control | Behavior |
| --- | --- |
| **O** (rebindable under XNMC) | Toggle: to the destination if one is set, otherwise forward |
| `/ghastpilot forward` | Fly the way you face; turn to steer. It handles height and terrain |
| `/ghastpilot goto <x> <z>` | Fly there at the height you're at now |
| `/ghastpilot goto <x> <y> <z>` | Fly there at height y |
| `/ghastpilot clear` / `off` / no argument | Drop the destination / stop / status |

It turns at most 6° a tick and turns in place when more than 30° off course, so it can't orbit the target. It climbs
when terrain within 10 blocks ahead would hit the ghast's body and settles back to the cruise height once the way down
is clear; holding jump raises that height. It stops on arrival (within 2 blocks), when you press back or when you
dismount.

## AFK Guard (`xnmc/afkguard`)

Arm it before going AFK on `justanotherhardcore.net:25565`; any damage logs you out, and a server restart or idle kick
reconnects you. A trip only turns it off, so nothing reconnects automatically and you rejoin from the server list.

| Command | Behavior |
| --- | --- |
| `/afkguard` | Status |
| `/afkguard arm` | Arm for 12 hours; needs full health, food ≥18/20 and no hurt animation |
| `/afkguard off` | Turn off; also cancels a pending reconnect |
| `/afkguard test` | The real damage logout, without the damage |
| `/afkguard test-restart` | Disconnect as a benign restart; the first reconnect comes 30 seconds later |

Armed, it logs you out on the first health or absorption loss or new hurt animation, at health ≤16/20, at food ≤12/20,
or after 12 hours (disconnected time included). Leaving the disconnect screen yourself turns it off, and so does a game
restart. While a reconnect is pending, the disconnect screen shows the countdown.

Reconnects only follow recognized shutdown, restart, maintenance and idle-kick messages, including JAH's
`✘ Proxy shutting down.`. Connection refused or timed out keeps an existing reconnect chain going but doesn't start one;
anything else (unknown messages, bans, death, operator kicks) turns it off. Backoff is 30, 60, 120, then 240 seconds,
up to 120 attempts within the 12 hours. A rejoin keeps the pre-kick health baseline, so damage during the gap still
trips. A client mod can't undo damage the server already applied or act while the client is frozen.

Auto Eater 1.5.1 (installed separately, from Modrinth) pairs with it: `.minecraft/config/auto_eater.json` scans the
inventory and eats at 18/20, in the main hand, using the last hotbar slot. Keep cooked food on you and face open air
when AFK; the guard's food cutoff is the fallback.
