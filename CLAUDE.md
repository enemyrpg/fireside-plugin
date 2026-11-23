# Fireside – RuneLite plugin

Collects data from Old School RuneScape (XP, loot, kill counts, sessions,
AFK time, world info, bank, quests, diaries and pets) and sends it to a
self-hosted server (repo: `fireside`, cloned next to this repo in
`../fireside`). License: BSD-2.

Server docs (English): `../fireside/docs/`, in particular
`architecture.md`, `api.md`, `data-collected.md`, `legal.md` and the
decision records in `decisions/`.

The event format is defined in `fireside` (the Zod schemas in
`../fireside/shared/` and `../fireside/docs/api.md`). A pinned copy of the
contract package (JSON Schema + fixtures) lives in
`src/test/resources/contract/`. Legal constraints:
`../fireside/docs/legal.md`.

## Commands
- `./gradlew build` – build and test
- `./gradlew run` or the shared **RuneLite (dev)** run configuration in
  IntelliJ (`.run/`) – start RuneLite with the plugin in developer mode
  (`-ea`, `--developer-mode`, `--debug`); see "Development" in
  `CONTRIBUTING.md`
- `./gradlew runIsolated` or **RuneLite (dev, isolated)** – the same with a
  separate RuneLite directory (`user.home` = `~/.runelite-fireside-dev`), so
  config, queue and plugin data are separate from `./gradlew run`
- `./scripts/update-contract.sh <version>` – update the pinned contract
- `./scripts/render-icons.sh` – render `icon.png` and the panel icon from
  `assets/logo/fireside.svg`

## Environment
- Developer machine: Arch Linux, fish shell, JDK 11, IntelliJ. The developer
  normally plays through the Bolt launcher. Give shell commands in fish syntax.
- The dev client logs in with a Jagex account from its own login screen.
- Bolt's RuneLite and the dev client use different RuneLite directories.
- Default branch: `master`.

## Rules
- The plugin is called "Fireside". Display name, internal name (`fireside`,
  also the plugin data folder), config group name (`fireside`) and queue
  folder name live in one constants class:
  `src/main/java/com/fireside/Branding.java`. Never hardcode them elsewhere.
  The config group name is final and must never change (users would lose
  their settings). The old working title is taken on the Plugin Hub and must
  not appear anywhere in the repo: `git grep -i -E "osrs[- ]?tracker"` must
  find nothing.
- Files that cannot read `Branding`: `runelite-plugin.properties`,
  `settings.gradle`, `group` in `build.gradle`, the Java package name and
  class names, `README.md`. Keep them consistent with `Branding`.
- No runtime dependencies beyond what runelite-client provides. Use the
  injected `OkHttpClient` and `Gson`. Test-only dependencies (e.g. a JSON
  Schema validator, a mock HTTP server) are allowed.
- Plugin Hub restrictions: no Java reflection, no JNI, no running external
  programs, no downloading code at runtime.
- Never block the client thread. Network and disk IO run off-thread on the
  plugin's own executor.
- Nothing is sent until server URL and API key are configured.
- Every event gets a UUIDv7 `eventId` and goes to the on-disk queue before
  sending. Event IDs MUST be monotonic: strictly increasing within the same
  millisecond, in game order (RFC 9562 monotonic counter). The server orders
  events by (`occurredAt`, `eventId`). Only remove events the server
  confirmed (`accepted` or `duplicate`). Timestamps are UTC with exactly three
  decimals and `Z`.
- The queue lives in the plugin's data folder under RuneLite's own
  directory (`RUNELITE_DIR/plugin-data/<internal name>/`, from
  `Plugin.getPluginDirectory()` and RuneLite's `Filepath`; queue folder name
  from `Branding`), never under `user.home`.
- If the server's contract version (from `verify`) is older than the plugin
  requires, keep the queue and do not send. Never move such events to the
  error log.
- Serialized events must validate against the pinned JSON Schema (tests).
- Never log the API key. Never log chat messages or anything else that may
  contain other players' names, not even at debug level.
- Follow Jagex's third-party client rules. The plugin only reads and reports
  data about the user's own accounts. It must never automate input, change
  menus, reveal hidden information or give gameplay advantages.
- Never collect or send data about other players (names, locations, gear).
  Loot from players is sent without the player's name. Raid purples are
  `SELF` or `TEAMMATE`; names are compared in memory and then discarded.
- AFK detection is statistics only. No in-game alerts based on it.
- Prefer generic detection (e.g. kill count chat pattern) so new game content
  works without code changes.
- Loot comes from the Loot Tracker's `LootReceived` event. Warn in the panel
  if the event depends on the Loot Tracker plugin and it is disabled.
- Reference implementations in RuneLite: Chat Commands (KC parsing),
  Loot Tracker (loot sources), Raids (CoX), Bank plugin (bank value, potion
  storage). Keep the BSD-2 copyright notice if code is copied. Check the
  license of any Plugin Hub plugin (e.g. Raid Data Tracker) before copying.
- No Jagex graphics in the repo. The panel icon is the Fireside logo
  (our own SVG, exported to PNG).
- README and Plugin Hub description include the Jagex Fan Content Policy
  disclaimer and state that data is only sent to a server the user sets up.
  The repo must be public before Plugin Hub submission.
- Code, comments and commit messages in English.
- release-please owns the plugin version (`build.gradle`,
  `runelite-plugin.properties`, `PluginVersion.java`, `version.txt`). Never
  edit it by hand.

## Workflow
- One task per branch and PR (e.g. `docs/after-release`).
- Never push directly to `master`; every change goes through a PR.
- PR titles follow Conventional Commits (`feat: …`, `fix: …`, `docs: …`),
  checked by the PR title workflow. PRs are squash merged, so the title is
  the commit message on `master` and release-please builds the changelog
  and the next version from it.
- The version is owned by release-please (see Rules). Its release PR is
  merged by the developer.
- Start with a plan, then tests, then implementation.
- Features must be tested in-game before merge (the developer does this).
- `../fireside-plugin-test` is the developer's test worktree. Never change,
  build or run anything there.
- Never change the default `./gradlew run` so it uses a different RuneLite
  directory; the test client depends on it.

## RuneLite plugin guidelines

@AGENTS.md
