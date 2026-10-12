# CloverRep 1.8.0 — dependency and runtime update

Checked against publisher Maven metadata and official release notes on 2026-10-12.

| Component | Previous | Current | Decision |
| --- | --- | --- | --- |
| Paper API | 26.2.build.116-stable | 26.2.build.133-stable | Keep the 26.2 minimum, verify 26.3.build.177-stable too |
| Gradle | 9.7.1 | 9.8.1 | Current stable wrapper with distribution checksum |
| Protobuf Java | 4.31.1 (transitive) | 4.36.2 | Current supported 4.x runtime, compatible with older Java generated code |
| Caffeine | Not used | 3.3.0 | Bounded, concurrent caches and timed refresh bookkeeping |
| PlaceholderAPI | 2.12.3 | 2.12.3 | Already current stable |
| HikariCP | 7.1.0 | 7.1.0 | Already current stable |
| MySQL Connector/J | 26.7.0 | 26.7.0 | Already current stable |
| Shadow | 9.6.1 | 9.6.1 | Already current stable |
| Java | 25 | 25 | Paper's required LTS runtime; no unnecessary language-version increase |

Versions are pinned. Maven's `latest` value is not used blindly: Paper metadata also contains alpha/pre-release builds. Adventure is provided by Paper rather than bundled, avoiding conflicts with the server's version.

## Runtime changes

- SQL reads, votes, saves, health checks and backups run in a bounded, single-worker queue. This preserves ordering and removes runtime disk/network waits from the server tick thread. Initial storage creation and legacy migration still happen during plugin startup.
- Bukkit player access is captured before queueing; replies, sounds and scoreboards return to the server thread. The worker operates on UUIDs, names and storage data.
- Reputation and identity caches are bounded to 10,000 entries with 15-minute idle expiry. Active scores refresh after 30 seconds, returning the previous value during refresh. Cold offline lookups return a temporary default and coalesce background loads. Failed lookups back off for 30 seconds and never cache an error as a real zero.
- Vote restrictions are enforced by storage, not a separate check followed by a write. MySQL commits the vote and increment together; staff bypass adjustments also run in a transaction. Duplicate keys are handled explicitly instead of suppressing other SQL errors with `INSERT IGNORE`.
- Existing SQL updates no longer use MySQL's deprecated `VALUES(column)` form. Name lookup can use the existing name index. Names are not overwritten with UUID fallback strings during an increment.
- Local votes guard integer overflow before recording history. Corrupt YAML stops initialization instead of being silently overwritten. Migration first preserves the original file and creates normalized empty sections.
- Backups fail when pending data cannot be saved, use unique filenames and preserve a consistent players/votes snapshot. Shutdown drains work, saves remaining changes and closes storage. Placeholder expansions unregister on disable.
- Sounds use Paper's typed registry access; legacy Bukkit sound names still work and reflection is cached. Existing HEX/legacy color configuration and placeholder names remain supported.

## Validation

JUnit regression tests cover nonblocking/coalesced reads, failures and retries, queue ordering and overload, shutdown, persistent votes, backups, malformed YAML, rename indexes, integer bounds and exact Adventure RGB colors. MySQL integration tests use an isolated MySQL 8.4 database and check simultaneous duplicate votes, concurrent increments, transaction rollback, pool failures and backup contents.

CI builds and runs tests against both pinned stable Paper APIs. Release artifacts use the 26.2 API for the lower supported minimum. Manual smoke tests additionally load the packaged plugin on actual Paper servers with PlaceholderAPI.

## Publisher references

- [Paper project setup](https://docs.papermc.io/paper/dev/project-setup/)
- [Paper scheduling](https://docs.papermc.io/paper/dev/scheduler/)
- [Paper download service](https://docs.papermc.io/misc/downloads-service/)
- [Protobuf supported versions](https://protobuf.dev/support/version-support/)
- [Protobuf runtime compatibility](https://protobuf.dev/support/cross-version-runtime-guarantee/)
- [Caffeine 3.3.0](https://github.com/ben-manes/caffeine/releases/tag/v3.3.0)
