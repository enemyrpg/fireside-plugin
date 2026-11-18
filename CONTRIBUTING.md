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
`Contract.REQUIRED_VERSION`.

**Icons:** `icon.png` (Plugin Hub) and the side panel icon are rendered from
`assets/logo/fireside.svg` with `./scripts/render-icons.sh` (needs
`rsvg-convert`). See `assets/logo/README.md`.
