# Contributing to Fireside – RuneLite plugin

## Development
Requires JDK 11. If your default Java is not 11, add
`org.gradle.java.home=/path/to/jdk-11` to `~/.gradle/gradle.properties`.

```sh
./gradlew build         # build and run tests
./gradlew run           # start RuneLite with the plugin (-ea --developer-mode --debug)
./gradlew runIsolated   # the same, with a separate RuneLite folder (see below)
```

**IntelliJ:** open the project as a Gradle project and set the project SDK to
11. Pick one of the shared run configurations (from `.run/`) and click Run or
Debug. They pass the same flags as the Gradle tasks. Do not use the ▶ next to
`main()` in `FiresidePluginTest`; that starts the client without the flags.

**Logging in:** log in with your Jagex account from the development client's
login screen. See
[Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts).

**Two run modes.** RuneLite keeps everything in `<user.home>/.runelite/`:
settings and profiles for all plugins, the plugin's data folder and queue,
logs and the game cache.

| Gradle task | IntelliJ | RuneLite folder |
| --- | --- | --- |
| `./gradlew run` | **RuneLite (dev)** | `~/.runelite/` |
| `./gradlew runIsolated` | **RuneLite (dev, isolated)** | `~/.runelite-fireside-dev/.runelite/` |

The isolated mode starts Java with `-Duser.home=~/.runelite-fireside-dev`, so
its Fireside settings (server URL, API key) and queue
(`~/.runelite-fireside-dev/.runelite/plugin-data/fireside/queue/`) are
completely separate. Use it to point a branch build at a development server
while another client sends to your real server. Trade-offs:
- You log in again in the isolated client; it has its own session.
- Every other plugin starts with default settings. Copy `profiles2/` from
  `~/.runelite/` if you want them.
- The game cache is downloaded again on first start.
- If you log in to a RuneLite account there, a synced profile brings its
  settings (including Fireside's) along. Do not sync the profile you use in
  the isolated client.

A client started from a launcher such as Bolt may use yet another RuneLite
folder, so its settings and queue are separate too. Do not log in with the
same character in two clients at the same time.

**Contract:** the event and API contract from the server repo is pinned in
`src/test/resources/contract/`, and tests validate against it. Update it with
`./scripts/update-contract.sh <version>` (needs `gh`), then update
`Contract.PINNED_VERSION`, and the other versions in `Contract` only if the
plugin now needs the new contract.

**Icons:** `icon.png` (Plugin Hub) and the side panel icon are rendered from
`assets/logo/fireside.svg` with `./scripts/render-icons.sh` (needs
`rsvg-convert`). See `assets/logo/README.md`.

## Issue types and labels
- **Type:** every issue has one GitHub issue type: Bug, Feature or Task.
  The issue forms set Bug or Feature. Docs and CI work is a Task.
- **What it is about:** `documentation` (docs only) and `ci` (workflows,
  build and tooling), on issues with the type Task. `question` for a
  question about use or setup.
- **Area:** usually one: `area: loot`, `area: sessions`, `area: bank`,
  `area: sync`.
- **Cross-cutting:** `accessibility` (barriers for people with disabilities),
  alongside the area.
- **Status**, only when it applies: `needs decision`, `needs info`,
  `blocked`, `good first issue`, `help wanted`.
- **Cross-repo:** `needs server` when the issue also needs a change in
  [fireside](https://github.com/enemyrpg/fireside) (server, contract or
  dashboard). Link the issues with GitHub's "blocked by" relationship, so the
  one that has to land first blocks the other.
- **Priority**, only when it applies: `priority: high` or `priority: low`.
  No priority label means normal.
- **On closing:** `wontfix` (close as "not planned" and link the ADR in the
  server's [docs/decisions/](https://github.com/enemyrpg/fireside/tree/main/docs/decisions)
  it goes against) or `duplicate` (link the original issue).
- Set automatically, don't add them by hand: `dependencies` and `java`
  (Dependabot), `autorelease: pending` and `autorelease: tagged`
  (release-please).

## Pull requests and releases
- One task per pull request. Larger changes: open an issue first.
- PR titles follow [Conventional Commits](https://www.conventionalcommits.org/)
  (`feat: …`, `fix: …`, `docs: …`, `ci: …`), checked by the **PR title**
  workflow. The type follows the issue's type, not its labels: a Bug is
  usually a `fix:`, a Feature a `feat:`, and a Task `docs:`, `ci:`, `chore:`
  or another type that fits. PRs are squash merged, so the title becomes the commit message on
  `master`, and the changelog is built from it.
- [release-please](https://github.com/googleapis/release-please) owns the
  version. Never edit it by hand. It keeps an open release PR that bumps the
  version in `build.gradle`, `runelite-plugin.properties`, `PluginVersion.java`
  and `version.txt` and updates `CHANGELOG.md`. Merging that PR tags
  `v<version>` and creates a GitHub release. Before 1.0.0, `feat` bumps the
  minor version and `fix` the patch version.
- A release has no artifacts: the Plugin Hub builds the plugin from a commit.
  After a release, update `commit=` in `plugins/fireside` in
  `runelite/plugin-hub` to the release commit with a pull request.
