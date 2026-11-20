# Fieldnotes AFK Guard

Custom client mod for Minecraft **26.3** (Fabric Loader 0.20.1, Fabric API 0.165.0+26.3, Java 25), scoped to
`justanotherhardcore.net:25565`. Arm it before going AFK; any damage logs you out, and a server restart or idle kick
reconnects you.

0.2.0 cut 0.1.0's persistent lock: a trip used to block every connection, your own included, until you found an
Acknowledge button on a separate screen. Now a trip only turns the guard off, so nothing reconnects automatically, and
you rejoin from the server list as usual. It has no buttons and nothing on disk; it says one line when you arm it and
when it rejoins, and the disconnect screen shows the countdown while a reconnect is pending.

| Command | Behavior |
| --- | --- |
| `/afkguard` | Status |
| `/afkguard arm` | Arm for 12 hours; needs full health, food ≥18/20 and no hurt animation |
| `/afkguard off` | Turn off; also cancels a pending reconnect |
| `/afkguard test` | The real damage logout, without the damage |
| `/afkguard test-restart` | Disconnect as a benign restart; the first reconnect comes 30 seconds later |

Armed, it logs you out on the first health or absorption loss or new hurt animation, at health ≤16/20, at food ≤12/20,
or after 12 hours (disconnected time included). Leaving the disconnect screen yourself turns it off, and so does a
game restart.

Reconnects only follow recognized shutdown, restart, maintenance and idle-kick messages, including JAH's
`✘ Proxy shutting down.`. Connection refused or timed out keeps an existing reconnect chain going but doesn't start one.
Anything else turns it off: unknown messages, bans, death and operator kicks. Backoff is 30, 60, 120, then 240 seconds,
up to 120 attempts within the 12 hours. A successful rejoin keeps the pre-kick health baseline, so damage during the gap
still trips.

## Auto Eater

Installed separately from [the author's Auto Eater project](https://github.com/nicolas-reig/Auto-Eater), official Modrinth version **1.5.1**, SHA-512 verified. `.minecraft/config/auto_eater.json` enables inventory scanning and sets `threshold: 2`, meaning eat when **two hunger points are missing** (18/20 remaining). It uses the main hand and does not swap out an offhand totem. It can move inventory food into the last hotbar slot; keep that slot available. Comma toggles its kill switch; controls are also available through Mod Menu.

Blacklisted foods include golden/enchanted apples, chorus fruit, rotten flesh, suspicious stew, pufferfish, poisonous potatoes, spider eyes and raw meat/fish. Keep cooked food in your inventory and face open air rather than an interactive container when AFK; manual interaction or damage can cancel eating. If eating fails or food runs out, the guard's hunger cutoff is the fallback.

## Validation

`python build.py` compiles against the installed game jars and runs the policy checks; `python smoke.py` loads the
mixins and entrypoint under Fabric in a separate game directory, without starting the game or connecting. Neither
covers live damage or reconnects. A client mod can't undo damage the server already applied or act while the client is
frozen.

## Files

Source is in `src/main/java`; tests are in `src/test/java`. The build artifact is `build/fieldnotes-afk-guard-0.2.0+26.3.jar`. The `guard-smoke.jar` test helper belongs only inside the isolated smoke directory and must never be copied into the real mods folder.
