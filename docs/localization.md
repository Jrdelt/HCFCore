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

Messages are namespaced by feature area in every `lang/*.yml` file, such as
`ability`, `admin`, `collector`, `combat`, `faction-bank`,
`faction-permissions`, `native-factions`, `portals`, `sandbot`, `staff`, and
`zones`. `lang/en_us.yml` is the authoritative complete set; translations
may omit newer keys and automatically fall back to English.

## GUI small caps

Vertex automatically converts static GUI titles, item names, and lore to its
small-caps alphabet. Write normal readable text in `lang/en_us.yml`; formatting
tags and placeholders are protected. For example, `Level {level} for {player}`
renders the words in small caps while the actual level and player name remain
unchanged. `%placeholder_api_tokens%` are protected in the same way.

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

Some GUI lore entries are YAML lists rather than one message per line. For
example, `faction-upgrades.gui.lore` contains the complete editable lore for
each upgrade state; preserve its list structure when translating it.
