# Fireside – RuneLite plugin

A RuneLite plugin that collects your own data from Old School RuneScape (XP,
loot, kill counts, raids, bank, quests, diaries, pets, sessions, AFK time and
world info) and sends it to a self-hosted Fireside server, which shows it on
your own dashboard.

Nothing is sent anywhere until you set a server URL and an API key and turn
on **Send data**. Data is only sent to a server you or someone you trust runs.

> **Status:** the Fireside server is not public yet. Until it is, the plugin
> is only useful if someone has given you access to their server. Without a
> server, the plugin does nothing.

## What is sent, and where
Everything goes to the server URL you enter, and nowhere else. There is no
central Fireside service and no telemetry. Like any web request, the server
can see your IP address.

- **Account:** RuneLite's account hash (to tell your accounts apart), display
  name, account type (e.g. ironman), world and the plugin version.
- **Sessions:** login, logout and world hops, a heartbeat every minute with
  region, world type and whether you are idle, and AFK periods.
- **XP:** XP and level per skill at login, at logout and every 5 minutes if
  it changed.
- **Loot and kill counts:** the source, items and prices from RuneLite's Loot
  Tracker, kill counts, kill times and personal bests, and raid purples.
- **Bank, quests, diaries and pets:** see **Data** below.
- **Icons:** item, skill and boss icons from your own client, so the
  dashboard can show them.

Never sent: other players' names (loot from players has no name, and a raid
purple is only "you" or "a teammate"), the text of chat messages, and your
Jagex password or login. Fireside never asks for them.

## Installation
Install **Fireside** from the Plugin Hub in RuneLite (the wrench icon →
**Plugin Hub**, search for "Fireside").

## Configuration
1. In your Fireside dashboard, create an API key under **Settings → API
   keys**. On a server run by someone else, the admin may create it for you.
2. In the plugin settings, set **Server URL** to the server's base address,
   e.g. `http://127.0.0.1:3100` for a server on your own PC, or the address
   you were given (without `/api/v1`), and paste the **API key**.
3. Turn on **Send data**. It is off by default, and nothing is sent until the
   server URL and API key are set. Data is only sent to the server you set up.
4. Open the plugin's side panel. The plugin checks the connection by itself
   (at start-up, a couple of seconds after you change the settings, and at
   login), and every answer from the server updates the status: **Connected**
   with the time of the last contact, **retrying** while the server can't be
   reached, **Server busy** when it asks the plugin to wait, or what is wrong (invalid API key, wrong server URL, server too
   old). It also warns if your clock is more than a minute off, or if the key
   would be sent unencrypted (plain `http` to another machine). Press
   **Test connection** to check again at once.

## Data
Under **Data** in the settings you can turn off XP, loot, kill counts, raid purples, bank,
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

## Disclaimer
Created using intellectual property belonging to Jagex Limited under the terms
of Jagex's Fan Content Policy. This content is not endorsed by or affiliated
with Jagex.

## License
[BSD 2-Clause](LICENSE)

Building the plugin yourself: see [CONTRIBUTING.md](CONTRIBUTING.md).
