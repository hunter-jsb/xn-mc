# XNMC

Our Fabric client tools in one mod, for Minecraft 26.3 (Fabric Loader 0.20.1, Fabric API 0.165.0+26.3, Java 25).
Part of xn-mc; see the root README for a sparse checkout that fetches only this folder.

Build with `gradle build` (Loom 1.19; it runs AFK Guard and Stasis policy checks, portal/frontier checks, item-tag matching checks, and the installed Baritone API check when available), then copy
`build/libs/xnmc-<version>.jar` into `~/.var/app/com.mojang.Minecraft/.minecraft/mods/` with the game closed.

## The menu

**;** (rebindable under XNMC), `/xnmc`, or Mods → XNMC → Configure opens a list of features, each with its status;
click one for its page of buttons. Buttons act and close the menu; replies land in chat. Nothing is added to the
title or pause screens. A new feature is a package with `init()` and `feature()`, listed in
`Xnmc.FEATURES`. Intentional Stasis recalls add a countdown and Cancel button to the disconnect screen.

## Stasis (`xnmc/stasis`)

Named, server-specific logout-recall destinations with measured timing windows and a one-shot reconnect.
Use `/stasis add home`, `/stasis test <seconds>`, `/stasis result recalled|missed|wrong-destination`,
then `/stasis window home <min-seconds> <max-seconds>` once measured. `/stasis recall home` reconnects
at the midpoint; `/stasis cancel` stops a pending countdown. The menu provides selection and window editing.
Drafts cannot recall; overlapping windows are rejected. Failed connections and missed windows never retry.
Intentional recalls turn AFK Guard and Meteor AutoReconnect off; they stay off until manually re-enabled.
No automatic damage recall or manual-login lock is added.

See [circuit trace, timing estimates, expansion plan and calibration](docs/stasis.md).
The existing schematic suggests a roughly 1.4–6 second circuit window **after hook release** at 20 TPS;
that is not a verified logout interval. No timing is preconfigured from this estimate.

## Portal Watch (`xnmc/portalwatch`)

The main page has **Start search**, **Stop**, **Watch only**, and **Build portal here**.
**Settings** holds the portal mode, search pattern, and trail inset; **Travel to coordinates** holds Go to and Spawn.
Settings are saved. Pattern and inset apply when starting a new search; portal mode applies immediately.

Toggle/status messages contain only On/Off, portal scope, and strategy, for example
`On · All Nether portals · Trail toward spawn`. Internal classification-loading state is not displayed.

Search patterns:

- **Trail / Away from spawn** follows connected, previously-generated terrain toward a generated/fresh boundary.
- **Trail / Toward spawn** uses the same trail-following logic with an inward preference.
- **Trail / No spawn bias** follows nearby usable trail boundaries without radial preference.
- **Spiral sweep** searches in a square spiral, with lanes twice the effective view distance apart.

Trail following starts on the Nether roof and reads XaeroPlus Palette NewChunks caches independently of display colors.
Automatic inset aims up to view distance minus two chunks into the generated side (capped at twelve chunks), so the edge
remains visible while more of the interior is loaded. Edge, two-chunk, and four-chunk insets are also available.
Narrow trails reduce the inset; paths favor moving inward before advancing along the boundary.
Unknown/conflicting classifications remain unknown, and generated/unclassified boundaries are labelled weak evidence.
Classification is not proof of first generation, a player's route, or who visited.

Search coverage records chunks actually loaded and scanned while trail exploration is running. It is saved per server
and dimension, survives new trips/restarts, and rejects destinations whose visible footprint has already been scanned.
This records new searches from this version onward; it cannot reconstruct previous expeditions. Some transit through
searched chunks is necessary to reach an unsearched area. Completed short-leg targets are excluded for the current trip;
if another frontier uses the same completed leg, Explore tries an alternative or stops instead of ping-ponging.
Generation classifications and search coverage are separate.

Explore reads the current dimension's world border and keeps targets four blocks inside it. Near the border (at least
128 blocks, extended by view distance), outward bias relaxes so routes can run sideways or inward. Inward bias remains
inward. Outside-border cells never count as new coverage or boundary evidence. The current goal is rechecked for border
changes; thirty seconds without half a block of progress cancels the trip. Spiral stops before a leg would cross the border.

The **Portal search** selector in Settings retains Roof (Y128+), Below roof, and Both. It filters portal finds, not travel height;
Overworld searches are unaffected. Finds ring a bell and stop the search. Lit portals at the same chunk but different
heights are detected separately. A reachable portal is approached to three blocks short; it is never entered automatically.
While on the roof, an underground find triggers construction in that portal's chunk, on continuous bedrock at Y128.

### Portal build macro

`/build` or `/portalwatch build` builds a ten-obsidian portal at your current height in your current chunk, without a
remembered find. Works in the Overworld or Nether on a clear, flat, solid-backed site; it does not dig, overwrite a build,
or travel below the roof for an automatic underground find. Portals cannot activate in the End.
Automatic roof builds still require a continuous bedrock floor. Manual builds accept other solid dry floors, and can
create a separate portal even when another exists in the chunk, provided there is room.

Carry **10 obsidian, 10 ordinary support blocks, and flint and steel or a fire charge**, in the main inventory/hotbar.
Support blocks may be mixed: dirt, coarse dirt, stone, cobblestone, deepslate, cobbled deepslate, netherrack, blackstone,
basalt, smooth basalt, granite, diorite, andesite, or tuff. Four supports form the frame corners; the rest are available
for scaffolding. Placed supports stay. The builder lights the frame and stops outside; linking must be checked manually.

Building cancels the travel route first. Dismount onto solid ground before starting. Breaking, downward movement, and
parkour are disabled during construction; previous Baritone settings are restored afterward. Damage, low food, an open
container, world changes, nearby lava, cancellation, or timeouts stop the build and leave placed blocks intact.
The build has plan and API checks; live placement still needs a supervised in-game check.

| Command | Behavior |
| --- | --- |
| `/portalwatch` | Status |
| `/portalwatch settings` | Portal mode, search pattern, and trail inset |
| `/portalwatch explore` | Start the selected search pattern |
| `/portalwatch off` | Stop watching, routing, or building |
| `/portalwatch on` | Watch only while you move; existing loaded portals are ignored |
| `/build` or `/portalwatch build` | Build a portal here |
| `/portalwatch settings mode <roof/below/both>` | Select which Nether portal heights to find |

Existing command shortcuts remain available: `sweep` selects and starts Spiral, `goto <x> <z>` and `spawn` watch while
travelling. Resume, Scan, and Build Above have been removed; start a new search to continue with saved coverage.
These are not separate primary actions on the main page. Travel legs use Ghast Pilot when riding a happy ghast and
Baritone on foot. Finds are remembered for the current dimension session; saved search coverage survives reconnects and restarts.

## Loot Notes (`xnmc/lootnotes`)

`/lootnotes hand` records the held item's lore and custom data. `/lootnotes chest` records item data from an already-open
chest menu, excluding player-inventory slots. `/lootnotes nearby` records recent chest/barrel metadata actually received
in chunk/block-entity packets within 256 blocks in currently loaded chunks. Nothing is automatically opened or moved.
Reports stay local in `.minecraft/xnmc/loot-notes/`. Vanilla chest chunk data doesn't expose its inventory; absent metadata
does not mean unlooted. "Crafted by" identifies an item's maker, not the person who opened the chest. "Looted by" text
is retained if actually observed, without assuming the tag exists or is authoritative.

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
- drops junk when fewer than 3 inventory slots are free and there's junk to drop: it pauses, waits until you're still, turns back down the dug
  tunnel, opens the inventory and throws netherrack, blackstone, basalt, gravel, flint, soul sand/soil, magma and
  cobblestone a stack every 4 ticks, keeping the biggest netherrack stack for Baritone to bridge with, then carries on;
- stops when AFK Guard turns off (damage logs you out), no hotbar pickaxe has more than 10 uses left, there's no empty
  slot or debris stack with room (a full inventory is fine otherwise), Baritone stays stuck past three skipped waypoints, or the 16 lanes are done;
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
