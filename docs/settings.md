# Personal settings

`/settings` (alias `/preferences`) opens each player's personal interaction
and notification preferences. Every toggle starts enabled. Choices are stored
per player across reconnects and restarts.

## Command and permission

| Command | Permission | What it does |
|---|---|---|
| `/settings` / `/preferences` | Open to all | Opens personal interaction and notification toggles. |

| Toggle | What it controls |
|---|---|
| Global Chat | Whether the player receives public global-chat messages. |
| Trade Requests | Whether other players can send trade requests. `/tradetoggle` changes this same setting. |
| Notifications | Master opt-out for nonessential Vertex announcements. |
| Private Messages | Whether other players can send direct messages. |
| Payments | Whether other players can send `/pay` transfers. |
| Teleport Requests | Whether other players can send `/tpa` requests. |
| Coinflip Announcements | Newly created public coinflips |
| KOTH Announcements | Scheduled KOTH and Mine KOTH captures, including Mine KOTH theft warnings |
| Outpost Announcements | Outpost start, capture, and end messages |
| Mining Event Announcements | Mining Hot Zone notices |
| Server Announcements | Reboot countdown notices |

When a player opts out of an interaction, the sender receives a clear message
and the recipient receives no blocked-request notification. Settings do not
hide punishment, combat, or required transaction-completion messages.
