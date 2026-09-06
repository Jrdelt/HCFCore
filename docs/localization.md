# Localization

Every player-facing message lives in `lang/*.yml`, never hardcoded in
Java. Four languages ship by default:

| Code | Language |
|---|---|
| `en_us` | English |
| `es_us` | Spanish |
| `pt_br` | Portuguese (Brazil) |
| `de_de` | German |

`language.default` in `config.yml` sets the language assigned to a
brand-new player. Players change their own with `/language [code]` — run
with no argument, it shows the current locale and every option available;
with a code, it switches and **persists the choice to the database**, so
it survives restarts and follows the player across sessions.

## Adding a new language

1. Copy `lang/en_us.yml` to `lang/xx_xx.yml` (your language code).
2. Translate every value — keys and placeholders must stay unchanged.
3. Run `/vertex reload` — the new language becomes selectable
   immediately, no restart needed.

## Message categories

Messages are namespaced by feature area in every `lang/*.yml` file:
`ability`, `admin`, `blueprint`, `collector`, `combat`, `cooldowns`,
`factions`, `general`, `kit`, `language`, `reboot`, `spawner`, `staff`,
`tags`. A few templates are the exception and live in `config.yml`
instead — the combat action bar (`pvp.actionbar`) and Archer Tag's hit
messages (`archer-tag.message-attacker` / `message-victim`) — since
those are single admin-authored templates rather than a per-locale
message set.

## Color codes

- MiniMessage tags: `<red>`, `<green>`, `<gradient:#RRGGBB:#RRGGBB>`,
  `<bold>`, etc.
- Semantic colors: `<success>`, `<deny>`, `<info>`, `<warning>` — use
  these instead of hardcoding red/green so a server can re-theme its
  message colors in one place.
- Legacy codes (`&4`, `&a`, ...) still work.
- Legacy hex: `&#FF5555`.

## Placeholders

Each message defines its own set — `{kit}`, `{player}`, `{seconds}`,
`{faction}`, and so on. Check `lang/en_us.yml` for the exact placeholders
a given message key supports before translating or customizing it.
