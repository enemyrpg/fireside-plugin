# OSRS Tracker – RuneLite plugin

> **Working title.** "OSRS Tracker" is a placeholder name until the first release.

A RuneLite plugin that collects data from Old School RuneScape (XP, loot, kill
counts, sessions, AFK time and world info) and sends it to your own
self-hosted [OSRS Tracker server](https://github.com/enemyrpg/osrs-tracker).
Nothing is sent anywhere until you configure a server URL and API key.

This plugin is in early development and does not collect anything yet.

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

## Disclaimer
Created using intellectual property belonging to Jagex Limited under the terms
of Jagex's Fan Content Policy. This content is not endorsed by or affiliated
with Jagex.

## License
[BSD 2-Clause](LICENSE)
