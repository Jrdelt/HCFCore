# Tags & Cosmetics

An equippable, cosmetic name-tag system with a searchable `/tags` GUI.
Six gradient-colored tags ship by default in `tags.yml` (Berserker,
Venom, Ruin, Frostbite, Eclipse, Vortex).

## `tags.yml` format

```yaml
tags:
  legend:
    display: "<gradient:#facc15:#f97316>Legend"
    permission: vertex.tag.legend
    created-at: 1725235200000
    owners: 0
    lore:
      - '<gray>A name known by all.'
players: {}
```

| Field | Meaning |
|---|---|
| `display` | The tag's name **and** its color/gradient in one MiniMessage (or legacy `&`) string — there's no separate color field. Sorting, searching, and nickname-matching all resolve the leading color/gradient out of `display` automatically. |
| `permission` | Blank means unlocked for everyone (same convention as kits); otherwise required to unlock/equip the tag. |
| `created-at` | Epoch milliseconds; shown in the GUI as `MM/yy`. |
| `owners` | A lifetime counter of how many times players have equipped this tag — not a live "currently equipped" count; unequipping doesn't roll it back. |
| `lore` | Optional extra flavor lines shown under the equipped/unequipped status line. |
| `material` / `custom-model-data` | Still read and round-tripped, but currently unused — every tag renders as a plain `NAME_TAG` icon in the GUI, including locked ones. |
| `players` | Per-player state (equipped tag id, nickname-match on/off, nickname-match reversed), keyed by UUID. Managed entirely by the plugin — not meant for hand-editing. |

## The `/tags` GUI

A 4×9 grid of tag icons (rows 2–5) with a control row:

- **Filter** — Your / Unowned / All, each showing a live count. Opens to
  **Your Tags** by default.
- **Sort** — Alphabetical / Age / Rarity, click to cycle, shift-click to
  flip direction. Rarity orders by lifetime owner count, fewest first
  when ascending.
- **Search** — opens a free anvil prompt to type a query; right-click
  clears it. Closing the anvil applies whatever was typed exactly once.
- **Prev / next page.**
- **Nickname-match** (top row, your own head as the icon) — recolors
  your name in chat to match your equipped tag's color/gradient,
  including a "reversed" gradient-direction option. Its preview renders
  your *actual* chat line — real faction tag, equipped cosmetic tag, and
  rank, through the live `chat.faction-format` / `chat.rank-format`
  templates — not a fixed example.

There's no close button — leave the GUI normally (Esc / click outside).
Clicking an unlocked tag equips it and announces it in chat (`<tag>
EQUIPPED`); clicking your already-equipped tag unequips it (`<tag>
UNEQUIPPED`).

Everything reloads live with `/vertex reload`, same as kits and
abilities.
