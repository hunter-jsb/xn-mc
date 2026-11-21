# xn-mc

Minecraft odds and ends for Xandaris. The server is wound down; its bot, chatbots, Discord and
Statuspage infra, map auth and revival plugin were removed on 2026-11-20 and live in history at
`a198dbf`.

| Path | What |
| --- | --- |
| [`client-mods/`](client-mods/) | XNMC, our Fabric client mod: Portal Watch, Ghast Pilot, AFK Guard |
| [`scripts/mods/`](scripts/mods/README.md) | Pinned Fabric client profiles downloaded from Modrinth |
| [`scripts/backup.py`](scripts/README.md) | World backup and restore to S3 |
| [`docs/`](docs/) | xandaris.space (GitHub Pages) |

To work on the client mods without the rest, use a sparse checkout:

```bash
git clone --filter=blob:none --sparse https://github.com/hunter-jsb/xn-mc.git
cd xn-mc && git sparse-checkout set client-mods
```
