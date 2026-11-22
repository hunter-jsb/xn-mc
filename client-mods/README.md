# XNMC

Our Fabric client tools in one mod, for Minecraft 26.3 (Fabric Loader 0.20.1, Fabric API 0.165.0+26.3, Java 25).
Part of xn-mc; see the root README for a sparse checkout that fetches only this folder.

Build with `gradle build` (Loom 1.19; it also runs AFK Guard's policy checks), then copy
`build/libs/xnmc-<version>.jar` into `~/.var/app/com.mojang.Minecraft/.minecraft/mods/` with the game closed.

## The menu

**;** (rebindable under XNMC), `/xnmc`, or Mods → XNMC → Configure opens a list of features, each with its status;
click one for its page of buttons. Buttons act and close the menu; replies land in chat. Nothing is added to the
title, pause or disconnect screens. A new feature is a package with `init()` and `feature()`, listed in
`Xnmc.FEATURES`.

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

## Debris Miner (`xnmc/debris`)

Tunnels the nether at Y 15 along chunk borders and mines the ancient debris it can see from the tunnel: only blocks
with a face open to air in your line of sight, within 5 blocks, never a beeline to buried ore. Baritone walks and digs
(`#goto` legs 8 blocks long); the miner plans the lanes and the safety.

| Command | Behavior |
| --- | --- |
| `/debris` | Status |
| `/debris start [length]` | Start from the nearest chunk-border crossing, tunnelling the way you face: 16 lanes of `length` blocks (default 256), one chunk apart, stepping to your right |
| `/debris resume` | Carry on the last run, past whatever stopped it |
| `/debris stop` | Stop |

To start it needs the nether, Y within 8 of 15, a pickaxe with durability in the hotbar, fire resistance with 2+
minutes left (or a fire resistance potion in the hotbar), and AFK Guard, which it arms if it can. It sets Baritone's
`itemSaver` so the pickaxe is never used up, and warns if you aren't wearing gold for piglins.

Running, it:
- backs off along the tunnel to the newest waypoint at least 6 blocks away with no lava within 3, then stops, when lava
  is in or beside your space or flowing at or above your feet within 2 blocks;
- drinks a fire resistance potion from the hotbar when under 45 seconds remain, and stops if there's none;
- drops junk when fewer than 3 inventory slots are free: it pauses, waits until you're still, turns back down the dug
  tunnel, opens the inventory and throws netherrack, blackstone, basalt, gravel, flint, soul sand/soil, magma and
  cobblestone a stack every 4 ticks, keeping the biggest netherrack stack for Baritone to bridge with, then carries on;
- stops when AFK Guard turns off (damage logs you out), no hotbar pickaxe has more than 10 uses left, the inventory is
  still nearly full after dropping junk, Baritone stays stuck past three skipped waypoints, or the 16 lanes are done;
- skips debris that touches lava, since opening it would let the lava in, and debris it can't reach in a minute.

Stops ring three bells.

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

## Ghast Pilot (`xnmc/ghastpilot`)

Autopilot for riding a happy ghast, after Ghast Autopilot (which stops at 1.21.11). Input-level: it holds forward and
jump and turns your view, and the ghast flies where you look, as if you were flying it. Rarely used, so it has no key and sits last in the menu.

| Control | Behavior |
| --- | --- |
| `/ghastpilot forward` | Fly the way you face; turn to steer. It handles height and terrain |
| `/ghastpilot goto <x> <z>` | Fly there at the height you're at now |
| `/ghastpilot goto <x> <y> <z>` | Fly there at height y |
| `/ghastpilot off` / no argument | Stop / status |

It turns at most 6° a tick and turns in place when more than 30° off course, so it can't orbit the target. It climbs
when terrain within 10 blocks ahead would hit the ghast's body and settles back to the cruise height once the way down
is clear; holding jump raises that height. It stops on arrival (within 2 blocks), when you press back or when you
dismount.
