# OSRS Tracker – RuneLite plugin

> **Working title.** "OSRS Tracker" is a placeholder name until the first release.

A RuneLite plugin that collects data from Old School RuneScape (XP, loot, kill
counts, sessions, AFK time and world info) and sends it to your own
self-hosted [OSRS Tracker server](https://github.com/enemyrpg/osrs-tracker).
Nothing is sent anywhere until you configure a server URL and API key.

This plugin is in early development and does not collect anything yet.

## Configuration
1. In your dashboard, create an API key under **Settings → API keys**.
2. In the plugin settings, set **Server URL** to the server's base address,
   e.g. `http://127.0.0.1:3000` (without `/api/v1`), and paste the **API key**.
3. Open the plugin's side panel and press **Test connection**. It shows
   whether the server answers and the key works, and warns if your clock is
   more than a minute off, if the server is too old for the plugin, or if
   the key would be sent unencrypted (plain `http` to another machine).
4. Turn on **Send data**. It is off by default, and nothing is sent until the
   server URL and API key are set. Data is only sent to the server you set up.

Under **Data** you can turn off XP, loot, kill counts and raid purples.
Sessions and AFK time are always sent while sending is on.

## Development
Requires JDK 11. If your default Java is not 11, add
`org.gradle.java.home=/path/to/jdk-11` to `~/.gradle/gradle.properties`.

```sh
./gradlew build   # build and run tests
./gradlew run     # start RuneLite with the plugin (-ea --developer-mode --debug)
```

**IntelliJ:** open the project as a Gradle project and set the project SDK to
11. Pick the shared **RuneLite (dev)** run configuration (from `.run/`) and
click Run or Debug. It passes the same flags as `./gradlew run`. Do not use the
▶ next to `main()` in `OsrsTrackerPluginTest`; that starts the client without
the flags.

**Logging in:** log in with your Jagex account from the development client's
login screen. See
[Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts).

The development client uses `~/.runelite/`. A client started from a launcher
such as Bolt may use a different RuneLite folder, so settings and the plugin's
queue are separate. Do not log in with the same character in both clients at
the same time.

**Contract:** the event and API contract from the server repo is pinned in
`src/test/resources/contract/`, and tests validate against it. Update it with
`./scripts/update-contract.sh <version>` (needs `gh`), then update
`Contract.REQUIRED_VERSION`.

## Disclaimer
Created using intellectual property belonging to Jagex Limited under the terms
of Jagex's Fan Content Policy. This content is not endorsed by or affiliated
with Jagex.

## License
[BSD 2-Clause](LICENSE)
