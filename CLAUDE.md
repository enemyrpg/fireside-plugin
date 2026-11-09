# OSRS Tracker (working title) – RuneLite plugin

Collects data from Old School RuneScape (XP, loot, kill counts, sessions,
AFK time, world info, later bank, quests and diaries) and sends it to a
self-hosted server (repo: `osrs-tracker`, cloned next to this repo in
`../osrs-tracker`). License: BSD-2.

Plans and decisions: `../osrs-tracker/docs/plan/` (Norwegian).
`../osrs-tracker/docs/plan/07-byggeplan.md` lists tasks and acceptance criteria.

The event format is defined in `osrs-tracker` (`shared/` and
`../osrs-tracker/docs/plan/03-datamodell-og-api.md`). A pinned copy of the
contract package (JSON Schema + fixtures) lives in
`src/test/resources/contract/` (added in task 7.3). Legal constraints:
`../osrs-tracker/docs/plan/09-juridisk.md`.

## Commands
- `./gradlew build` – build and test
- `./gradlew run` or run from IntelliJ – start RuneLite in developer mode
- `./scripts/update-contract.sh <version>` – update the pinned contract
  (added in task 7.3)

## Environment
- Developer machine: Arch Linux, fish shell, JDK 11, IntelliJ. The developer
  normally plays through the Bolt launcher. Give shell commands in fish syntax.
- Default branch: `master`.

## Rules
- "OSRS Tracker" is a placeholder name. Keep the display name, config group
  name and queue folder name in one constants class:
  `src/main/java/com/osrstracker/Branding.java`. Never hardcode them elsewhere.
  The config group name is set for good before the first public release and
  must never change after that (users would lose their settings).
- Files that cannot read `Branding` and must be renamed by hand (task 8.0):
  `runelite-plugin.properties`, `settings.gradle`, `group` in `build.gradle`,
  the Java package name and class names, `README.md`.
- No dependencies beyond what runelite-client provides. Use the injected
  `OkHttpClient` and `Gson`.
- Plugin Hub restrictions: no Java reflection, no JNI, no running external
  programs, no downloading code at runtime.
- Never block the client thread. Network and disk IO run off-thread.
- Nothing is sent until server URL and API key are configured.
- Every event gets a UUIDv7 `eventId` and goes to the on-disk queue before
  sending. Only remove events the server confirmed (`accepted` or `duplicate`).
- Serialized events must validate against the pinned JSON Schema (tests).
- Follow Jagex's third-party client rules. The plugin only reads and reports
  data about the user's own accounts. It must never automate input, change
  menus, reveal hidden information or give gameplay advantages.
- Never collect or send data about other players (names, locations, gear).
  Loot from players is sent without the player's name.
- AFK detection is statistics only. No in-game alerts based on it.
- Prefer generic detection (e.g. kill count chat pattern) so new game content
  works without code changes.
- Reference implementations in RuneLite: Chat Commands (KC parsing),
  Loot Tracker (loot sources), Bank plugin (bank value, potion storage).
  Keep the BSD-2 copyright notice if code is copied.
- README and Plugin Hub description include the Jagex Fan Content Policy
  disclaimer and state that data is only sent to a server the user sets up.
  The repo must be public before Plugin Hub submission.
- Code, comments and commit messages in English.

## Workflow
- One task per branch and PR (e.g. `m7/event-queue`).
- Start with a plan, then tests, then implementation.
- Features must be tested in-game before merge (the developer does this).

## RuneLite plugin guidelines

@AGENTS.md
