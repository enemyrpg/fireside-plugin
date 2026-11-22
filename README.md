# Fireside – RuneLite plugin

A RuneLite plugin that collects your own data from Old School RuneScape (XP,
loot, kill counts, raids, bank, quests, diaries, pets, sessions, AFK time and
world info) and sends it to a self-hosted
[Fireside](https://github.com/enemyrpg/fireside) server, which shows it on
your own dashboard.

Data is only sent to a server that you, or someone you trust, runs. There is
no central Fireside service and no telemetry. Nothing is sent until you set a
server URL and an API key and turn on **Send data**.

**Contents**

1. [You need a server](#you-need-a-server)
2. [Installation](#installation)
3. [Getting started](#getting-started)
4. [Settings](#settings)
5. [What is sent, and where](#what-is-sent-and-where)
6. [Queue and sending](#queue-and-sending)
7. [Compatibility](#compatibility)
8. [Disclaimer](#disclaimer)
9. [License](#license)

## You need a server
The plugin does nothing on its own. It sends to a Fireside server: the server
and dashboard are in [enemyrpg/fireside](https://github.com/enemyrpg/fireside).
To run your own, follow
[Self-hosting Fireside](https://github.com/enemyrpg/fireside/blob/main/docs/self-hosting.md).
You can also use a server run by someone you trust, if they give you access.

## Installation
**From the Plugin Hub:** in RuneLite, click the wrench icon, open
**Plugin Hub**, search for "Fireside" and install it.

**From source (for development):** you need JDK 11.

```sh
git clone https://github.com/enemyrpg/fireside-plugin.git
cd fireside-plugin
./gradlew run
```

This starts RuneLite in developer mode with the plugin loaded. See
[CONTRIBUTING.md](CONTRIBUTING.md) for how to log in, the isolated run mode
and the tests.

## Getting started
1. In your Fireside dashboard, create an API key under **Settings → API
   keys**. On a server run by someone else, the admin may create it for you.
2. In the plugin settings, set **Server URL** to the server's base address,
   e.g. `http://127.0.0.1:3100` for a server on your own PC, or the address
   you were given such as `https://dashboard.example.com` (without
   `/api/v1`), and paste the **API key**.
3. Turn on **Send data**.
4. Open the plugin's side panel. The plugin checks the connection by itself
   (at start-up, a couple of seconds after you change the settings, and at
   login), and every answer from the server updates the status: **Connected**
   with the time of the last contact, **retrying** while the server can't be
   reached, **Server busy** when it asks the plugin to wait, or what is wrong
   (invalid API key, wrong server URL, server too old). It also warns if your
   clock is more than a minute off, or if the key would be sent unencrypted
   (plain `http` to another machine). Press **Test connection** to check
   again at once.

## Settings

### Server

| Setting        | Default | What it does                                                                                                                                                   |
| -------------- | ------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Server URL** | empty   | Base address of your Fireside server, without `/api/v1`. The plugin only talks to this host, accepts plain `http(s)` addresses only and never follows redirects |
| **API key**    | empty   | The key from your dashboard (**Settings → API keys**). Stored by RuneLite as a secret setting and never logged                                                  |
| **Send data**  | off     | Turns sending on. Nothing is sent until the server URL and API key are also set                                                                                |

### Data

Each kind of data can be turned off. Sessions and AFK time are always sent
while **Send data** is on. Bank, quests and diaries, and pets only run on
normal worlds (not Leagues, Deadman or beta worlds).

| Setting                | Default   | What it does                                                                                       |
| ---------------------- | --------- | -------------------------------------------------------------------------------------------------- |
| **XP**                 | on        | XP and level for every skill, at login, at logout and every 5 minutes if it changed                |
| **Loot**               | on        | Loot from RuneLite's Loot Tracker                                                                  |
| **Kill counts**        | on        | Boss and raid kill counts, kill times and personal bests, and raid PBs                             |
| **Raid purples**       | on        | Raid unique drops for you and your team, as "you" or "a teammate", never a name                    |
| **Bank**               | on        | Bank, potion storage, inventory and equipment when you close the bank and something changed        |
| **Quests and diaries** | on        | Quest states, quest points and completed achievement diary tiers, after login and when they change |
| **Pets**               | on        | Pet drops: which pet message the game showed, and a guess at the pet and where it came from        |
| **Upload item icons**  | on        | Icons from your own client, so the dashboard can show them                                         |
| **AFK threshold**      | 5 minutes | Minutes without input or XP before time counts as AFK (1–60). Statistics only, no in-game alerts   |

**Loot** needs RuneLite's Loot Tracker plugin. The side panel warns if it is
turned off.

**Kill counts** also covers raid PBs: when you open The Fractured Archive's
records table yourself, your own PBs per team size are sent. The plugin
never opens the table for you. It also covers the kill count attached to
loot and raid purples.

**Bank** sends your bank as the game has it: tabs, order, placeholders and
bank fillers. A full bank is about 160 KB, so at most one snapshot per
account is sent every 15 minutes; a change inside that time is sent when the
15 minutes are up, or when you log out, hop or close the client. The plugin
only reads the game's item containers and bank tab settings, never the bank
layout of other plugins such as Bank Tags. If the bank looks different from
what the plugin expects, nothing is sent and the side panel says so.

**Quests and diaries** is sent after login, once the game has loaded your
quest data, and again when something changes (a started quest can take up to
five minutes), but only if it differs from the last time for that account.

**Pets** reacts to the game's three pet messages ("You have a funny feeling
like you're being followed", "You feel something weird sneaking into your
backpack" and "You have a funny feeling like you would have been followed").
Only which of the three it was is sent, never the chat text. The plugin adds
the pet when the game says which it is (the collection log's "New item added
to your collection log" line, a pet appearing in your inventory, or your new
follower's name), and a guess at where it came from: the kill count, loot or
skill XP closest in time to the message. Either is left out when the plugin
does not know, and a duplicate pet is sent without a pet. Pets are
recognised most reliably when the game's chat notification for new
collection log items is on (in the game's settings, under **Chat**:
"Collection log - New addition notification"). The source guess may use the
name of your latest loot or kill even when **Loot** or **Kill counts** is
off, but only inside the pet event.

**Upload item icons** uploads the icons of the items in your loot and bank.
The plugin asks the server which icons it lacks and uploads only those, at a
lower priority than your data. After you log in, it also checks the icons of
every collection log item, every skill and every boss and raid on the
hiscores. The first login uploads what the server lacks (about 1,800 small
images, spread over a minute or so); later logins only ask and upload
nothing. Icons are only stored on your server.

## What is sent, and where
Everything goes to the server URL you set, and nowhere else. Like any web
request, the server can see your IP address.

- **Account:** RuneLite's account hash (to tell your accounts apart), display
  name, account type (e.g. ironman), world and the plugin version.
- **Sessions:** login, logout and world hops, a heartbeat every minute with
  region, world type and whether you are idle, and AFK periods.
- **The data you turned on** under [Data](#data), and icons from your own
  client.

Never sent: other players' names (loot from players has no name, and a raid
purple is only "you" or "a teammate"), the text of chat messages, and your
Jagex password or login. Fireside never asks for them.

The full list, field by field, and what the server stores and for how long:
[What data Fireside collects](https://github.com/enemyrpg/fireside/blob/main/docs/data-collected.md).

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

## Compatibility

| Plugin | Contract built and tested against | Fireside server |
| ------ | --------------------------------- | --------------- |
| 0.1.0  | 1.41.0                            | 0.1.0 or newer  |

Servers with contract 1.17.0 or newer still receive data, but features that
need a newer contract are skipped until the server is updated.

## Disclaimer
Created using intellectual property belonging to Jagex Limited under the terms
of Jagex's Fan Content Policy. This content is not endorsed by or affiliated
with Jagex.

## License
[BSD 2-Clause](LICENSE). The server and dashboard,
[enemyrpg/fireside](https://github.com/enemyrpg/fireside), are MIT licensed.
