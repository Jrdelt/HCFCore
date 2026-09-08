# GUI framework

Every new Vertex menu is defined in `gui/<menu>.yml` rather than in Java.
Size, title, slots, materials, custom model data, names, lore, and click
sounds are all yours to change without touching code.

Existing menus predate this and are unchanged. New menus are built on it, so
the framework is not retrofitted onto working screens for its own sake.

## A menu file

```yaml
size: 27                      # 9-54, multiple of 9
title: "ʙᴏᴏsᴛᴇʀs"
titles:                       # optional alternates for other screens
  detail: "{category}"
refresh-ticks: 20             # re-render while open; 0 disables

filler:
  enabled: false
  material: GRAY_STAINED_GLASS_PANE
  name: " "

items:
  back:
    material: ARROW
    slot: 22
    name: "ʙᴀᴄᴋ"
    sound: minecraft:ui.button.click

layout:
  category-slots: [11, 12, 13, 14, 15]
```

Item keys: `material`, `custom-model-data`, `amount`, `name`, `lore`,
`slot` or `slots`, `enabled`, `sound`. All optional.

`enabled: false` removes an optional button entirely. Omitting `material` is
valid for a template the plugin renders many times with different icons — it
supplies one per instance.

## Static items vs. repeated templates

Two things a real menu needs, both supported:

- **Static** — a Back button with its own slot, placed once.
- **Repeated** — one template rendered many times with different data, such
  as a row of category icons. These take their slots from a named run under
  `layout:` and their icon from the plugin.

## Placeholders

Inline `{key}` substitutions are filled per render. Values are **escaped**
before insertion, so a player or faction name can never inject formatting
into your template.

A lore line consisting *only* of `{key}` may be a **block** placeholder: it
is replaced by however many lines the plugin produces, for genuinely
variable-length content like "one line per booster source". Blocks are built
in code and inserted as-is.

Each menu's file documents which placeholders it accepts.

## Small caps

The small-caps styling is literal text in the file, not a runtime
conversion — replace it with normal letters if you prefer. Nothing restyles
placeholder *values*, so a name always renders exactly as it is.

## Bad values never break a screen

Every invalid value falls back with a console warning naming the file, the
key, and what was used instead:

| Problem | Result |
|---|---|
| `size` not 9-54 in multiples of 9 | 27 |
| Unknown material | `STONE` |
| Unknown sound | no sound |
| Slot outside 0-53 | that slot ignored |
| Missing menu file | empty but usable layout |

A menu that opens looking wrong is far easier to diagnose than one that
silently refuses to open.

## Reloading

`/vertex reload` re-reads every `gui/*.yml`. Menus already open keep their
current contents until reopened.
