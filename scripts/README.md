# scripts

- `backup.py upload|download` zips the world to S3 or pulls it back (`uv run backup.py download`).
  Needs `WORLD_NAME`, `SERVER_FP` and `S3_BUCKET` in the repo-root `.env`. Old backups sit in
  S3 Deep Archive, so restore the object first.
- [`mods/`](mods/README.md) builds pinned Fabric client profiles from Modrinth.
