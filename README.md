<div align="center">

# CloverRep

**A small reputation system for Paper servers with persistent voting, PlaceholderAPI support and local or MySQL storage.**

[![Build](https://github.com/slyphmp4/CloverRep/actions/workflows/build.yml/badge.svg?branch=master)](https://github.com/slyphmp4/CloverRep/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/slyphmp4/CloverRep?style=flat-square)](https://github.com/slyphmp4/CloverRep/releases)
[![Java](https://img.shields.io/badge/Java-25-555?style=flat-square)](https://openjdk.org/)
[![Paper](https://img.shields.io/badge/Paper-26.2%20%7C%2026.3-555?style=flat-square)](https://papermc.io/)
[![Author](https://img.shields.io/badge/author-slyph-555?style=flat-square)](https://github.com/slyphmp4)

[Releases](https://github.com/slyphmp4/CloverRep/releases) · [Builds](https://github.com/slyphmp4/CloverRep/actions) · [Issues](https://github.com/slyphmp4/CloverRep/issues)

</div>

---

## Overview

CloverRep gives players a simple `+1 / -1` reputation system without turning reputation into a separate economy or progression system.

A vote changes the target's score by one point, self-voting is blocked, and the normal flow records one vote from a player to a target. Staff can bypass the vote restriction when necessary.

The score can then be used in chat, tab lists, scoreboards or other plugins through PlaceholderAPI.

### Features

- positive and negative player reputation;
- persistent voter/target tracking;
- permission-aware `/rep` tab completion;
- local YAML or MySQL storage;
- automatic fallback to local storage if MySQL cannot be initialized;
- configurable positive, neutral and negative colors;
- optional dark-nickname behavior below a reputation threshold;
- optional below-name scoreboard display;
- PlaceholderAPI expansion with direct player and UUID lookups;
- database health checks and backups;
- HEX colors in `&RRGGBB` and `&#RRGGBB` form.

---

## Requirements

| Component | Version / notes |
| --- | --- |
| Minecraft / Paper | **26.2 / 26.3**; built against stable 26.2 API build 133 |
| Java | **25** |
| PlaceholderAPI | Optional, built against **2.12.3** |

HikariCP, MySQL Connector/J, Protobuf and Caffeine are bundled and relocated inside the release JAR. They do not need to be installed separately. See the [dependency and architecture update](docs/MODERNIZATION.md) for exact versions and validation.

---

## Installation

1. Download the latest CloverRep JAR from [Releases](https://github.com/slyphmp4/CloverRep/releases), or build it from source.
2. Put the JAR into the server's `plugins/` directory.
3. Start Paper 26.2 or 26.3 on Java 25.
4. Configure `plugins/CloverRep/config.yml` and `messages.yml` if needed.
5. Restart the server or use `/cloverrep reload` for normal configuration changes.

The default storage backend is local YAML, so no database setup is required for a single server.

---

## Reputation flow

Players vote with:

```text
/rep + <player>
/rep - <player>
```

A normal vote follows these rules:

- the command can only be used by a player;
- the target must currently be online;
- players cannot vote for themselves;
- `+` adds one reputation point;
- `-` removes one reputation point;
- an already recorded voter/target pair cannot vote again unless the voter has the bypass permission.

This keeps the mechanic predictable and prevents a single player from repeatedly inflating or destroying somebody's score.

---

## Commands

| Command | Description | Permission |
| --- | --- | --- |
| `/rep + <player>` | Give one reputation point | `cloverrep.rep.plus` |
| `/rep - <player>` | Remove one reputation point | `cloverrep.rep.minus` |
| `/cloverrep reload` | Reload configuration | `cloverrep.admin.reload` |
| `/cloverrep status` | Show storage/runtime status | `cloverrep.admin.status` |
| `/cloverrep checkdb` | Check database health | `cloverrep.admin.checkdb` |
| `/cloverrep backup` | Create a storage backup | `cloverrep.admin.backup` |

`/cloverrep` also has the alias `/cr`.

### Permissions

| Permission | Default | Purpose |
| --- | --- | --- |
| `cloverrep.rep.plus` | Everyone | Give positive reputation |
| `cloverrep.rep.minus` | Everyone | Give negative reputation |
| `cloverrep.rep.bypass` | OP | Bypass the normal vote limit |
| `cloverrep.admin.reload` | OP | Reload CloverRep |
| `cloverrep.admin.status` | OP | View runtime/storage status |
| `cloverrep.admin.checkdb` | OP | Run a database health check |
| `cloverrep.admin.backup` | OP | Create a backup |

---

## Display behavior

Reputation colors are configured independently for positive, negative and neutral values:

```yaml
colors:
  reputation-positive: "&21FF5D"
  reputation-negative: "&FF2B2B"
  reputation-neutral: "&BFBFBF"
  dark-nick: "DARK_GRAY"
```

Both of these HEX forms are accepted:

```text
&FF0000
&#FF0000
```

### Dark nickname threshold

CloverRep can expose a darker nickname color when a player's reputation reaches a configured threshold.

```yaml
reputation:
  dark-nick-threshold: -20
```

The feature is deliberately exposed through placeholders as well, so another chat or tab plugin can decide how the nickname should actually be rendered.

### Below-name score

A lightweight scoreboard objective can display reputation below player names:

```yaml
display:
  below-name:
    enabled: false
    objective-id: "cloverrep"
    title: "&eRep"
    force-slot: false
```

It is disabled by default.

---

## PlaceholderAPI

When PlaceholderAPI is installed, CloverRep registers the `cloverrep` expansion automatically.

### Current player

| Placeholder | Result |
| --- | --- |
| `%cloverrep_reputation%` | Colored reputation value |
| `%cloverrep_reputation_raw%` | Raw integer |
| `%cloverrep_reputation_signed%` | Signed value such as `+12` or `-4` |
| `%cloverrep_nick_color%` | Resolved nickname color code |
| `%cloverrep_dark_nick%` | `true` when the dark-nick threshold is reached |
| `%cloverrep_nick%` | Player name with the resolved reputation-based color |

### Explicit player lookup

```text
%cloverrep_reputation_player_Notch%
%cloverrep_reputation_raw_player_Notch%
%cloverrep_reputation_signed_player_Notch%
%cloverrep_nick_color_player_Notch%
%cloverrep_dark_nick_player_Notch%
%cloverrep_nick_player_Notch%
```

### UUID lookup

```text
%cloverrep_reputation_uuid_<uuid>%
%cloverrep_reputation_raw_uuid_<uuid>%
%cloverrep_reputation_signed_uuid_<uuid>%
%cloverrep_nick_color_uuid_<uuid>%
%cloverrep_dark_nick_uuid_<uuid>%
%cloverrep_nick_uuid_<uuid>%
```

These variants are useful when another plugin is formatting information about somebody other than the placeholder context player.

Placeholder reads use bounded caches and never wait for SQL or contact Mojang. Online players are preloaded on join. The first lookup for an uncached offline player can return `0` (or an empty name) while the background load completes; the next lookup uses the loaded value. Unknown names remain unknown instead of creating an offline UUID. Cached scores refresh in the background after 30 seconds, keeping shared MySQL scores up to date without blocking a tick.

---

## Storage

### Local

The default setup uses YAML:

```yaml
storage:
  type: "local"
  auto-save-ticks: 600
```

Reputation data is stored under the CloverRep plugin directory and does not require an external service.

### MySQL

For shared or remote storage:

```yaml
storage:
  type: "mysql"

mysql:
  host: "localhost"
  port: 3306
  database: "minecraft"
  user: "root"
  password: "password"
  tablePrefix: "clover_"
```

The connection pool is configurable through the `mysql.pool` section.

Runtime storage operations run in one ordered background queue. Vote insertion and score changes commit together; a database error does not become a cached zero score. `mysql.connect-timeout-millis` and `mysql.socket-timeout-millis` default to 5000. `mysql.ssl-mode` defaults to `PREFERRED` and accepts Connector/J's standard TLS modes.

If CloverRep cannot initialize MySQL during startup, it falls back to local YAML instead of leaving the reputation system unusable.

Backups flush pending data and use distinct filenames. MySQL backups read players and votes from one consistent transaction. Shutdown drains queued work before the final save and closes the connection pool. A malformed local YAML file stops initialization and preserves the original data.

---

## Configuration files

```text
plugins/CloverRep/
├── config.yml
├── messages.yml
└── reputation.yml
```

- `config.yml` — storage, colors, display and technical settings.
- `messages.yml` — player-facing command and error messages.
- `reputation.yml` — local reputation data when `storage.type` is `local`.

---

## Building from source

CloverRep uses the Gradle Wrapper, Java 25 and Shadow.

```bash
git clone https://github.com/slyphmp4/CloverRep.git
cd CloverRep
./gradlew clean build
```

On Windows:

```powershell
.\gradlew.bat clean build
```

The shaded, self-contained JAR is written to `build/libs/`.

Gradle 9.8.1 is pinned with its distribution SHA-256. JUnit tests run as part of `build`. To also run the real MySQL tests, provide `CLOVERREP_TEST_MYSQL_URL`, `CLOVERREP_TEST_MYSQL_USER` and `CLOVERREP_TEST_MYSQL_PASSWORD` for an isolated test database; the test suite creates and removes its own randomly prefixed tables. CI runs these tests against MySQL 8.4 and both stable Paper API versions.

---

## Related Clover plugins

- [CloverChat](https://github.com/slyphmp4/CloverChat) can use `%cloverrep_nick%` and `%cloverrep_reputation%` directly in chat formatting.
- [CloverBadges](https://github.com/slyphmp4/CloverBadges) handles badges and cosmetic nickname/message colors.

CloverRep is maintained by **slyph**.
