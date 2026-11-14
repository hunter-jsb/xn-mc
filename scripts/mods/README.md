# Fabric client profiles

Profiles describe a Minecraft version, Fabric Loader version, and mod selection.
Lock files store the exact releases, HTTPS jar links, and SHA-512 checksums. Commit
both JSON files; downloaded jars and local instance data stay out of Git.

## Included profiles

| Profile | Adds | Inherits |
| --- | --- | --- |
| `26.3/default` | Fabric API, Sodium, Iris, Mod Menu, AppleSkin, Shulker Box Tooltip, Logical Zoom | — |
| `26.3/multiplayer` | Xaero's Minimap and World Map, MiniHUD, Totem Counter, Auto Logout | default |
| `26.3/farming` | Tweakeroo, Item Scroller | multiplayer |

Required Modrinth dependencies are included automatically. These profiles use
Fabric Loader 0.20.1. Auto Logout thresholds and Tweakeroo/Item Scroller controls
need configuring in-game; installing the profile does not configure or start
AFK automation. Durability Ping is not included because a 26.3 release was not
available when these profiles were created. Litematica and Baritone are not
included. Iris supplies shader support; shader packs are selected separately.

## Use a profile

Run from the repository root with Python 3.10 or newer (standard library only):

```bash
python3 scripts/mods/download_mods.py list
python3 scripts/mods/download_mods.py show --profile 26.3/multiplayer
python3 scripts/mods/download_mods.py download --profile 26.3/multiplayer
```

The last command uses the committed lock, without querying for newer releases.
It downloads into `scripts/mods/instances/26.3/multiplayer/mods/`. With no
arguments, the downloader builds `26.3/default`.

Each profile must have its own Minecraft **game directory**, not just a different
launcher name. Create a launcher installation using the matching Minecraft and
Fabric Loader versions, then set its Game Directory to the absolute path of
`scripts/mods/instances/26.3/multiplayer/`. Use the equivalent separate directory
for default and farming. Minecraft then keeps settings, maps, worlds, and other
instance data separate. This tool downloads mods; it does not install Fabric,
change launcher profiles, or migrate saves/configs.

For a Flatpak launcher, choose a game directory it can access, such as a directory
inside its existing `.minecraft` folder. For example, with the launcher closed:

```bash
python3 scripts/mods/download_mods.py download --profile 26.3/multiplayer \
  --output "$HOME/.var/app/com.mojang.Minecraft/.minecraft/instances/26.3/multiplayer/mods"
```

Set that launcher installation's Game Directory to the parent of this `mods`
folder. `--output` always names the **mods directory**, not the game directory.
The downloader refuses an existing nonempty directory it does not manage, a
folder belonging to a different profile, or one containing additional files.
Use a new empty directory for the initial build. Close the game before rebuilding.
Downloads finish and pass hash checks in a staging folder before the previous
managed mod set is replaced, so failed downloads leave it intact. Successful
rebuilds remove stale jars from that managed folder. Other instance data is left
alone. Back up any manually modified managed jars before rebuilding.

## Change a profile or create another version

Edit a profile, then explicitly refresh and commit its lock:

```bash
python3 scripts/mods/download_mods.py resolve --profile 26.3/multiplayer
python3 scripts/mods/download_mods.py resolve --profile 26.3/farming
python3 scripts/mods/download_mods.py download --profile 26.3/multiplayer
```

Changing a parent invalidates its descendants' locks too. Resolve every affected
profile. `resolve` chooses the latest stable Fabric release for the exact game
version, recursively includes required dependencies, checks declared Modrinth
incompatibilities, and fails on unavailable releases or conflicting exact pins.
This is metadata validation, not a full solver for version ranges inside jars;
Fabric Loader and an in-game smoke test are the final runtime compatibility check.
These initial profiles passed download/hash checks, but have not been launched
as a complete pack.

For another Minecraft version, create a new `profiles/<version>/<name>.json`:

```json
{
  "name": "Default Fabric",
  "mc_version": "26.1.2",
  "fabric_loader": "0.19.3",
  "mods": ["fabric-api", "sodium", "iris"]
}
```

Resolve with `--profile 26.1.2/default`. Different Minecraft or loader versions
use independent base profiles. Within one version, extend a base and optionally
remove or override individual selections:

```json
{
  "name": "Building without maps",
  "extends": "26.3/multiplayer",
  "remove": ["xaeros-minimap", "xaeros-world-map"],
  "mods": ["tweakeroo", "item-scroller"]
}
```

A mod entry can be a slug string or an object with `slug` and `version_id` to pin
a specific Modrinth version ID (including a compatible prerelease). Overrides
replace the inherited entry for that slug. Required dependencies can reintroduce
a removed root selection if another mod still needs it. Lock files are generated;
edit profile selections and re-resolve instead of editing lock metadata by hand.
Manual/non-Modrinth downloads are not resolved by this tool yet.

`manifest.json` is the preserved legacy mod catalog, including candidates that
have not been migrated. It is no longer an input to the downloader. The named
profile files are the source of truth for new downloads.

## Validation

```bash
python3 -m unittest discover -s scripts/mods -p 'test_*.py' -v
```
