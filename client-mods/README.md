# Client mods

Custom Fabric client mods for Minecraft 26.3 (Fabric Loader 0.20.1, Fabric API 0.165.0+26.3, Java 25).
Part of xn-mc; see the root README for a sparse checkout that fetches only this folder.

| Mod | What it does | Build |
| --- | --- | --- |
| [`afk-guard`](afk-guard/) | Logs out and locks on damage, low health or hunger while AFK on justanotherhardcore.net; reconnects after benign kicks | `python3 afk-guard/build.py` |
| [`portal-watch`](portal-watch/) | Stops Baritone and rings a bell when a nether portal loads in; roof-aware | `cd portal-watch && gradle build` |

Install by copying the built jar into `~/.var/app/com.mojang.Minecraft/.minecraft/mods/` with the game closed.
