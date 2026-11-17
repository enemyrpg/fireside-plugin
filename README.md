# Fireside – RuneLite plugin

A RuneLite plugin that collects data from Old School RuneScape (XP, loot, kill
counts, raids, bank, quests, diaries, pets, sessions, AFK time and world info) and sends
it to your own
self-hosted [Fireside server](https://github.com/enemyrpg/fireside).
Nothing is sent anywhere until you configure a server URL and API key.

This plugin is in early development.

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

Under **Data** you can turn off XP, loot, kill counts, raid purples, bank,
quests and diaries, and pets.
**Kill counts** also covers raid PBs: when you open The Fractured Archive's
records table yourself, your own PBs per team size are sent. The plugin
never opens the table for you.
Sessions and AFK time are always sent while sending is on.

**Bank** (on by default) sends your bank, potion storage, inventory and
equipment when you close the bank, but only if something changed since the
last time for that account. The bank is sent as the game has it: tabs, order,
placeholders and bank fillers. A full bank is about 160 KB, so at most one
snapshot per account is sent every 15 minutes; a change inside that time is
sent when the 15 minutes are up, or when you log out, hop or close the
client. The plugin only reads the game's item containers and bank tab
settings, never the bank layout of other plugins such as Bank Tags. It only
runs on normal worlds (not Leagues, Deadman or beta worlds). If the bank looks
different from what the plugin expects, nothing is sent and the side panel
says so.

**Quests and diaries** (on by default) sends the state of every quest and
miniquest (not started, in progress or finished), your quest points, and which
achievement diary tiers have all their tasks done. It is sent after login,
once the game has loaded your quest data, and again when something changes
(a started quest can take up to five minutes), but only if it differs from
the last time for that account. It only runs on normal worlds.

**Pets** (on by default) sends a pet drop when the game shows one of its
three pet messages ("You have a funny feeling like you're being followed",
"You feel something weird sneaking into your backpack" and "You have a funny
feeling like you would have been followed"). Only which of the three it was
is sent, never the chat text. The plugin adds the pet when the game says
which it is (the collection log's "New item added to your collection log"
line, a pet appearing in your inventory, or your new follower's name), and a
guess at where it came from: the kill count, loot or skill XP closest in
time to the message. Either is left out when the plugin does not know, and a
duplicate pet is sent without a pet. Pets are recognised most reliably when
the game's chat notification for new collection log items is on (in the
game's settings, under **Chat**: "Collection log - New addition
notification"). The source guess may use the name of your latest loot or
kill even when the **Loot** or **Kill counts** toggle is off, but only inside
the pet event. It only runs on normal worlds.

**Upload item icons** (on by default) uploads the icons of the items in your
loot and bank, taken from your own RuneLite client, so the dashboard can show them.
They are only sent to your own server, which stores them in its own
database. The plugin asks the server which icons it lacks and uploads only
those, at a lower priority than your data. A server too old for icons still
gets all your data.

After you log in, the plugin also checks the icons of every collection log
item, every skill and every boss and raid on the hiscores, so the dashboard
can show things your account has not had yet. The first login uploads what
the server lacks (about 1,800 small images, spread over a minute or so); later
logins only ask and upload nothing. These icons need a server with contract
1.29.0 or newer.

## Queue and sending
Events are written to a queue on disk first and sent in batches: every 30
seconds, when 50 events are waiting, at logout, and when you press **Send
now** in the side panel. An event is only removed from the queue once the
server has confirmed it, so nothing is lost while the server is down; the
plugin retries with increasing waits (up to 5 minutes).

The queue lives in RuneLite's own folder, in the plugin's data folder
(`.runelite/plugin-data/fireside/queue/`). The side panel shows the full
path.
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

## Disclaimer
Created using intellectual property belonging to Jagex Limited under the terms
of Jagex's Fan Content Policy. This content is not endorsed by or affiliated
with Jagex.

## License
[BSD 2-Clause](LICENSE)
