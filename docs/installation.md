# Installation

## Requirements

| | Requirement | Notes |
|---|---|---|
| Required | **Paper 1.21.10+** | The plugin targets Paper's API directly (not just Spigot/Bukkit). |
| Required | **FactionsUUID** | Must be installed and enabled before Vertex starts, or Vertex refuses to enable. |
| Optional | **MySQL 5.7+ or MariaDB** | Only if you set `storage.type: mysql`. By default Vertex uses a local SQLite file and needs no database server at all. See [Storage](#storage-local-or-mysql) below. |
| Optional | Vault, WorldGuard, LuckPerms, PlaceholderAPI, EssentialsX, FastAsyncWorldEdit, DecentHolograms | Each unlocks specific features. See [Integrations](integrations.md) for exactly what each one does and what happens without it. |

## Building from source

```bash
./mvnw clean package
```

This produces a single shaded jar at `Vertex/target/vertex-1.0.0.jar`.
HikariCP and both database drivers (SQLite and MySQL) are bundled and
relocated into `me.vertex.core.libs.*`, so no separate driver jar needs to
be dropped in alongside it.

To also run the MockBukkit test suite (no real server needed):

```bash
./mvnw clean test
```

## First deploy

1. Build (or download) `vertex-1.0.0.jar`.
2. Drop it into `plugins/`, alongside `FactionsUUID.jar`.
3. Start the server. Vertex generates `plugins/Vertex/config.yml` and
   every other resource file (`kits.yml`, `abilities.yml`, `tags.yml`,
   `spawners.yml`, `collectors.yml`, `blueprints.yml`, `lang/*.yml`),
   creates its local database at `plugins/Vertex/vertex.db`, and starts
   working immediately — there's nothing to configure first.
4. On a successful boot you'll see the Vertex banner in the console with
   the running version, before anything else loads.
5. Edit `config.yml` to taste and run `/vertex reload` — no restart
   needed for config changes.

## Storage: local or MySQL

`storage.type` in `config.yml` picks where data is saved. It defaults to
`local`, and anything other than the exact value `mysql` is treated as
local, so a typo can never leave the server unable to start.

| | `local` (default) | `mysql` |
|---|---|---|
| Setup | None. A `vertex.db` SQLite file is created in the plugin folder. | Install/reach a MySQL or MariaDB server, create a database, fill in the `mysql` section. |
| Best for | A single server — the normal case. | Several servers that must share the same player data (a proxied network), or an existing MySQL setup you'd rather keep everything in. |
| Backups | Copy `plugins/Vertex/vertex.db` while the server is stopped. | Your database server's own backup tooling. |

Both store exactly the same data with the same schema, and both are
equally supported — local is simply the default because it needs nothing
from you.

To use MySQL:

```yaml
storage:
  type: mysql

mysql:
  host: localhost
  port: 3306
  database: vertex
  username: vertex
  password: yourpassword
```

Create the database itself first (`CREATE DATABASE vertex;`) — Vertex
creates its own tables inside it, but not the database. If it can't
connect, Vertex logs the exact error and disables itself rather than
running half-initialized.

### Switching backends in-game

`/vertex storage` (permission `vertex.admin`) reports which backend is
live. `/vertex storage local` or `/vertex storage mysql` switches to the
other one:

1. It copies every row Vertex owns — kit and ability cooldowns, player
   locales, death history (item blobs included), spawners, chunk
   collectors, and in-progress blueprint builds — from the running
   backend into the target one, creating the target's tables first if
   needed. The copy runs off the main thread, so the server doesn't hang.
2. It writes `storage.type` into `config.yml`, editing only that one line
   so your comments survive.
3. **Restart the server** to actually start using the new backend. The
   switch is not hot-swapped; until the restart, the old backend is still
   the live one, so nothing is lost if the copy went wrong.

If the target database already contains Vertex data, the command refuses
and tells you how many rows are there — because migrating **replaces**
the target's contents. Re-run it as `/vertex storage <type> confirm` to
go ahead and overwrite. Switching to MySQL still requires the `mysql`
section to be filled in and the database to exist first.

The copy is idempotent: running it twice replaces the target's contents
again rather than duplicating rows.

Prefer running it while the server is quiet. The local backend uses a
single pooled connection (SQLite serializes writes anyway), so a large
death-history table being copied can briefly make gameplay writes queue
behind it.

If `FactionsUUID` isn't present and enabled, Vertex logs why and disables
itself immediately rather than running in a half-working state.

If `FactionsUUID` isn't present and enabled, Vertex logs why and disables
itself immediately rather than running in a half-working state.

## Migrating from an HCFCore-branded install

Earlier builds of this plugin shipped under the name **HCFCore**
(plugin id `HCFCore`, jar `hcfcore-1.0.0.jar`, data folder
`plugins/HCFCore/`, and every permission node under `hcfcore.*`). If
you're updating a live server from one of those builds, the rebrand to
Vertex needs two manual, one-time steps that a normal jar swap won't do
for you:

1. **Move the data folder.** Bukkit names a plugin's data folder after
   its registered name, so the new jar looks for `plugins/Vertex/`, not
   `plugins/HCFCore/`. Stop the server, rename
   `plugins/HCFCore/` to `plugins/Vertex/` (don't just copy — the old
   folder won't be read again otherwise), then start the server with the
   new jar. Your `config.yml`, `kits.yml`, `tags.yml`, `abilities.yml`,
   `spawners.yml`, `collectors.yml`, `blueprints.yml`, and `lang/`
   overrides all carry over untouched this way. This is a folder rename
   only — the MySQL database itself is unaffected, since it's addressed
   by the credentials in `config.yml`, not by the plugin's name.
2. **Re-grant permissions.** Every permission node changed from
   `hcfcore.*` to `vertex.*` (e.g. `hcfcore.kit.archer.donator` is now
   `vertex.kit.archer.donator`). Whatever granted the old nodes — a
   LuckPerms group, a permissions file, another plugin — needs the new
   `vertex.*` equivalents added; the old grants simply stop matching
   anything rather than erroring. See
   [Commands & Permissions](commands-and-permissions.md) for the
   complete current list.
3. **Keep using MySQL, if that's where your data is.** HCFCore builds were
   MySQL-only and had no `storage.type` setting, so an old `config.yml`
   won't contain one — and a missing `storage.type` now means *local*.
   Left alone, the server would quietly start against a brand-new, empty
   `vertex.db` file and every player's kits, cooldowns, spawners and
   collectors would look like they'd vanished (the MySQL data is still
   there, just not being read). Add this to the top of your `config.yml`
   before starting:

   ```yaml
   storage:
     type: mysql
   ```

A fresh install has none of this to worry about — it only applies when
upgrading a server that was already running the HCFCore-named build.

## Upgrading

1. Stop the server.
2. Replace the old jar with the new one. Keep the same file name pattern
   (`plugins/Vertex/` as the data folder) so existing config and data
   files are found.
3. Start the server and watch the console for the startup banner and any
   warnings.
4. Compare your `config.yml`, `kits.yml`, `abilities.yml`, etc. against
   the freshly-documented defaults for any new keys introduced by the
   update — existing files are never overwritten, so a new key from an
   update won't appear until you add it yourself.

**Database schema note:** Vertex performs its required schema migrations
on startup. In particular, an older `spawners` table automatically gains
the `owner_faction` column before any spawner data is read or written.
Back up databases before any update as normal, but no manual `ALTER TABLE`
is required for this release.

## Reload vs. restart

`/vertex reload` (permission `vertex.admin`) reloads `config.yml`,
`kits.yml`, `abilities.yml`, `tags.yml`, and every `lang/*.yml` file live,
and rebuilds the scoreboard for every online player — no restart needed
for content or message changes.

For anything that isn't a config/content change (a plugin update, a JVM
flag change, a dependency being added/removed), perform a full server
restart instead of `/reload`. A bare `/reload` doesn't give Paper,
FactionsUUID, or optional integrations a clean chance to tear down and
re-initialize their own state, and Vertex's own pending database writes
are only guaranteed to flush on a real shutdown.

## Verifying it's running

- The console prints a Vertex ASCII banner with the exact running version
  and a link back to the repository as the very first thing the plugin
  does on boot — if you don't see it, the server isn't running the jar
  you think it is.
- `/vertex reload` re-running without errors confirms the config parsed
  correctly.
- If a newly-added command doesn't show up or tab-complete, the most
  common cause is a stale jar still running on the server — redeploy the
  exact jar you just built and fully restart (not `/reload`) before
  troubleshooting further.
