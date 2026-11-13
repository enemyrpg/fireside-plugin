# OSRS Tracker – RuneLite plugin

> **Working title.** "OSRS Tracker" is a placeholder name until the first release.

A RuneLite plugin that collects data from Old School RuneScape (XP, loot, kill
counts, sessions, AFK time and world info) and sends it to your own
self-hosted [OSRS Tracker server](https://github.com/enemyrpg/osrs-tracker).
Nothing is sent anywhere until you configure a server URL and API key.

This plugin is in early development. The queue and sender are in place, but
it does not collect any game data yet.

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

## Queue and sending
Events are written to a queue on disk first and sent in batches: every 30
seconds, when 50 events are waiting, at logout, and when you press **Send
now** in the side panel. An event is only removed from the queue once the
server has confirmed it, so nothing is lost while the server is down; the
plugin retries with increasing waits (up to 5 minutes).

The queue lives in RuneLite's own folder, in the plugin's data folder
(`.runelite/plugin-data/osrs-tracker/queue/`). **Open folder** in the side
panel opens it.
- `pending.jsonl`: events waiting to be sent, one per line.
- `rejected.jsonl`: events the server rejected, each with the server's error
  message and the time. They are not sent again and never deleted
  automatically. The panel shows how many there are.

Sending stops if the server rejects the API key or answers 404 (usually a
wrong server URL). The panel says why. Fix the setting, or press **Test
connection** once it works, and sending resumes. If the server is older than
the plugin needs, events stay queued until the server is updated.

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
