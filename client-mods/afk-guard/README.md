# Fieldnotes AFK Guard

Custom client mod for Hunter's Minecraft **26.3**, Fabric Loader **0.20.1**, Fabric API **0.165.0+26.3**, Java **25**, and `justanotherhardcore.net:25565`. Installed disabled. This is an initial implementation, not yet validated in a running game on JAH.

One state machine owns safety monitoring and reconnects. A safety trip is written to disk **before** closing the connection, cancels queued retries, and blocks Minecraft's normal multiplayer connection entry point until explicitly acknowledged offline. While AFK is armed, connection attempts outside the guard’s approved retry path also lock; another reconnect mod cannot use that entry point to bypass classification. Killing/restarting Minecraft while armed also boots locked. Acknowledging a lock leaves monitoring disabled and never initiates a connection.

## Controls

An **AFK Guard** button appears on the title, pause, and disconnect screens. The disconnect screen shows monitoring state and reconnect countdown.

| Command | Behavior |
| --- | --- |
| `/afkguard` | Open the guard controls |
| `/afkguard arm` | Arm for 12 hours; requires full health, food ≥18/20 and no active hurt animation |
| `/afkguard off` | Disable normal monitoring/retries; cannot clear a safety lock |
| `/afkguard status` | Show current state |
| `/afkguard test` | Trigger the actual persistent safety logout without taking damage |
| `/afkguard test-restart` | Deliberately disconnect as a benign restart; first reconnect attempt after 30 seconds |

When armed, the first observed health/absorption loss or new hurt animation locks you offline. Independent cutoffs are health ≤16/20 (eight hearts), hunger ≤12/20 (six drumsticks), and 12 elapsed hours. The time limit includes disconnected time and uses the local monotonic clock. It must be armed explicitly for every new AFK session. Manual return to the title/server list cancels retries.

Initial reconnects are allowed for narrowly recognized server shutdown/restart/maintenance messages and idle kicks. This includes JAH's observed `✘ Proxy shutting down.` message. Connection-refused/time-out errors can continue an already approved retry chain; an initial time-out alone is not enough. Unknown messages, bans, death/hardcore notices, authentication failures and operator kicks lock you offline. Backoff is 30, 60, 120, then 240 seconds, capped at 120 attempts and the original session deadline. Successful rejoins retain monitoring and the previous health baseline; no invulnerable grace period is assumed.

State: `.minecraft/config/fieldnotes-afk-guard-state.properties`. Missing state starts OFF; unreadable/corrupt existing state starts LOCKED. Do not delete this file to automate clearing a lock. The mod has no reconnect-on-launch feature.

## Auto Eater

Installed separately from [the author's Auto Eater project](https://github.com/nicolas-reig/Auto-Eater), official Modrinth version **1.5.1**, SHA-512 verified. `.minecraft/config/auto_eater.json` enables inventory scanning and sets `threshold: 2`, meaning eat when **two hunger points are missing** (18/20 remaining). It uses the main hand and does not swap out an offhand totem. It can move inventory food into the last hotbar slot; keep that slot available. Comma toggles its kill switch; controls are also available through Mod Menu.

Blacklisted foods include golden/enchanted apples, chorus fruit, rotten flesh, suspicious stew, pufferfish, poisonous potatoes, spider eyes and raw meat/fish. Keep cooked food in your inventory and face open air rather than an interactive container when AFK; manual interaction or damage can cancel eating. If eating fails or food runs out, the guard's hunger cutoff is the fallback.

## Validation

Run `python build.py` for compilation against the locally installed game APIs and 45 state-machine/persistence checks. Run `python smoke.py` for Fabric loading and transformation of all guard mixins plus initialization of the guard entrypoint alongside the installed mod set, in a separate game directory. The smoke test exits before graphics initialization or connecting anywhere. It does not test gameplay, live packet delivery, actual screen interaction, or server behavior.

Before unattended use, stand safely at base, fully healed and fed:

1. Restart Minecraft to load the new jars. Confirm **AFK Guard: OFF** and Auto Eater is enabled.
2. Run `/afkguard arm`, then `/afkguard test`. Expect a safety logout, no reconnect countdown, and no automatic rejoin after waiting over a minute.
3. Restart Minecraft while still locked. Confirm a manual server connection is blocked and opens AFK Guard. Acknowledge the lock there; this must leave you offline with monitoring OFF.
4. Join manually at base. Arm, then run `/afkguard test-restart`. Expect a 30-second countdown, a reconnect, and monitoring still ARMED. Use `/afkguard status` to verify. Then `/afkguard off`.
5. Verify Auto Eater on safe ground, and the live health/food detection on a disposable local test world/server before trusting an overnight JAH session. The production guard intentionally arms only on the JAH address; use an isolated test build to change that restriction for a local server.

These checks remain to be performed in-game. A client mod cannot prevent damage already processed by the server, undo a fatal hit, or act while the client is frozen. A disconnect may also interact with the existing logout stasis chamber: the guard never promises which position a subsequent login will use.

## Files

Source is in `src/main/java`; tests are in `src/test/java`. The build artifact is `build/fieldnotes-afk-guard-0.1.0+26.3.jar`. The `guard-smoke.jar` test helper belongs only inside the isolated smoke directory and must never be copied into the real mods folder.
