package me.vertex.core.enchant;

import me.vertex.core.backpack.BackpackManager;
import me.vertex.core.lang.Messages;
import me.vertex.core.lang.MessageFormatter;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@code /seasonal roll item <enchant-id-or-catalog-id> [amount]} -- gives
 * the sender a random-level roll of one specific seasonal item, for admins
 * previewing seasonal content without picking an exact level themselves.
 * Each of {@code amount}'s copies rolls its level independently (not one
 * level applied to every copy), matching how a real crate roll would
 * behave. Accepts a catalog id (e.g. {@code fallen_helmet}) exactly like
 * {@code give} does -- see {@link #resolveSeasonalDefinition}.
 *
 * <p>{@code /seasonal item <material> <customModelData> [name]} -- a plain
 * blank item stamped with a material and custom model data (and optional
 * display name), for quickly staging a crate reward's display/win item so
 * it matches a resource pack texture. No lore or enchants.
 *
 * <p>{@code /seasonal give <id> [level] [amount]} is the one command that
 * covers every kind of seasonal reward, by checking three id spaces in
 * order:
 * <ol>
 *   <li>a {@code backpacks.yml} tier id (e.g. {@code fallen_crate}) -- built
 *       fresh every time via {@link BackpackManager#createBackpackItem(String, int)},
 *       since each backpack needs its own instance id; {@code [level]} is
 *       the backpack's starting level.
 *   <li>a seasonal Rune id from {@code customEnchants/runes.yml} (e.g.
 *       {@code huntmasters_call}) -- an identified copy at {@code [level]}
 *       (default 1), {@code [amount]} copies.
 *   <li>an id saved in the {@link SeasonalItemCatalog} -- {@code
 *       /seasonal catalog save <id>} snapshots whatever's in your hand
 *       (enchants, lore, custom model data, any other plugin's PDC, all of
 *       it) exactly as-is; {@code give} then reproduces that same item
 *       forever after. {@code [level]} there is read as {@code [amount]}
 *       instead, since a catalog item has no notion of a level.
 * </ol>
 * This is the actual "refill a crate easily" workflow for anything that
 * isn't a Rune: build the finished reward item by hand once, catalog it,
 * then {@code give} it back for players or for re-dropping into a crate
 * plugin's reward-item slot every season. {@code catalog save} itself
 * auto-attaches the matching ability whenever the held item's material is
 * compatible with exactly one seasonal Rune (bakes it on at that Rune's max
 * level before saving) -- no need to name the enchant by hand when the item
 * already tells you which one it is; a non-Rune item (the backpack) or an
 * ambiguous match just saves as-is, same as before. Once saved that way, no
 * further config wiring is needed either: {@link EnchantManager#createEnchantItem}
 * discovers that catalog entry on its own by scanning for whichever saved
 * item already carries the requested ability, so {@code give}/{@code roll}/
 * the Seasonal Set preview menu all pick up the real item automatically.
 *
 * <p>Shares {@link EnchantCommand#GIVE_PERMISSION} rather than a second,
 * parallel permission node -- this is the same admin/event distribution
 * surface as {@code /enchant give}, just with a randomized level instead of
 * an explicit one.
 */
public final class SeasonalCommand implements CommandExecutor, TabCompleter {

    private final EnchantManager manager;
    private final Messages messages;
    private final SeasonalItemCatalog catalog;
    private final BackpackManager backpackManager;

    public SeasonalCommand(EnchantManager manager, Messages messages, SeasonalItemCatalog catalog,
            BackpackManager backpackManager) {
        this.manager = manager;
        this.messages = messages;
        this.catalog = catalog;
        this.backpackManager = backpackManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get(sender, "general.players-only"));
            return true;
        }
        if (!player.hasPermission(EnchantCommand.GIVE_PERMISSION)) {
            player.sendMessage(messages.get(player, "general.no-permission"));
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("item")) {
            return handleItem(player, args);
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("give")) {
            return handleGive(player, args);
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("catalog")) {
            return handleCatalog(player, args);
        }
        if (args.length < 3 || !args[0].equalsIgnoreCase("roll") || !args[1].equalsIgnoreCase("item")) {
            player.sendMessage(messages.get(player, "seasonal.usage"));
            return true;
        }
        String id = args[2];
        EnchantDefinition definition = resolveSeasonalDefinition(id);
        if (definition == null) {
            player.sendMessage(messages.get(player, "enchant.unknown-seasonal-item", "id", id));
            return true;
        }
        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Integer.parseInt(args[3]);
                if (amount <= 0) {
                    player.sendMessage(messages.get(player, "seasonal.usage"));
                    return true;
                }
                amount = Math.min(amount, 64 * 6);
            } catch (NumberFormatException e) {
                player.sendMessage(messages.get(player, "seasonal.usage"));
                return true;
            }
        }
        int maxLevel = definition.maxLevel();
        List<ItemStack> items = new ArrayList<>(amount);
        for (int i = 0; i < amount; i++) {
            int rolledLevel = 1 + ThreadLocalRandom.current().nextInt(maxLevel);
            ItemStack created = manager.createEnchantItem(definition.id(), rolledLevel, RuneTier.SEASONAL);
            if (created != null) {
                items.add(created);
            }
        }
        if (items.isEmpty()) {
            player.sendMessage(messages.get(player, "enchant.unknown-seasonal-item", "id", id));
            return true;
        }
        me.vertex.core.storage.ItemGiver.give(player, items);
        player.sendMessage(messages.get(player, "seasonal.rolled", "amount", String.valueOf(items.size()),
                "enchant", RuneFormatting.coloredNameRaw(RuneTier.SEASONAL, definition.displayName())));
        return true;
    }

    /**
     * Accepts either a seasonal Rune id directly, or a catalog id -- looked
     * up by finding whichever ability is already baked onto that saved
     * item (there should only ever be one, since {@code catalog save}
     * bakes at most one ability per item). Lets {@code /seasonal roll item}
     * take the same catalog names {@code give} and {@code catalog} already
     * use, instead of requiring the underlying ability id.
     */
    private EnchantDefinition resolveSeasonalDefinition(String id) {
        EnchantDefinition direct = manager.definition(id);
        if (direct != null && manager.isSeasonal(id)) {
            return direct;
        }
        ItemStack catalogued = catalog.get(id);
        if (catalogued == null) {
            return null;
        }
        for (String enchantId : manager.enchantsOf(catalogued).keySet()) {
            EnchantDefinition candidate = manager.definition(enchantId);
            if (candidate != null && manager.isSeasonal(enchantId)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * {@code /seasonal item <material> <customModelData> [name]} -- staging
     * item for a crate reward slot: correct material and custom model data
     * so it renders with the intended resource pack texture, and an
     * optional display name (MiniMessage/legacy). No lore, no enchants, no
     * rune registry lookup -- the crate plugin's own reward editor captures
     * whatever this item looks like when handed over.
     */
    private boolean handleItem(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(messages.get(player, "seasonal.usage"));
            return true;
        }
        Material material = Material.matchMaterial(args[1].toUpperCase(Locale.ROOT));
        if (material == null || material.isAir()) {
            player.sendMessage(messages.get(player, "seasonal.unknown-material", "material", args[1]));
            return true;
        }
        int customModelData;
        try {
            customModelData = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            player.sendMessage(messages.get(player, "enchant.invalid-model-data"));
            return true;
        }
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setCustomModelData(customModelData);
        if (args.length >= 4) {
            String name = String.join(" ", Arrays.asList(args).subList(3, args.length));
            meta.displayName(MessageFormatter.deserialize(name));
        }
        item.setItemMeta(meta);
        me.vertex.core.storage.ItemGiver.give(player, List.of(item));
        player.sendMessage(messages.get(player, "seasonal.item-given",
                "material", material.name(), "cmd", String.valueOf(customModelData)));
        return true;
    }

    /**
     * {@code /seasonal give <id> [amount]} for a catalogued item, or
     * {@code /seasonal give <backpack-tier-id> [level]} for a backpack
     * tier -- the two id spaces are disjoint (a catalog id and a
     * {@code backpacks.yml} tier id can't collide without an admin
     * deliberately naming a catalog entry after a tier), so the tier check
     * runs first and everything else falls through to the catalog.
     */
    private boolean handleGive(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(messages.get(player, "seasonal.give-usage"));
            return true;
        }
        String id = args[1];

        if (backpackManager.isTier(id)) {
            int level = 1;
            if (args.length >= 3) {
                try {
                    level = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    player.sendMessage(messages.get(player, "enchant.invalid-level"));
                    return true;
                }
            }
            ItemStack backpack = backpackManager.createBackpackItem(id, level);
            me.vertex.core.storage.ItemGiver.give(player, List.of(backpack));
            player.sendMessage(messages.get(player, "seasonal.item-given",
                    "material", backpackManager.tierDisplayName(id), "cmd", String.valueOf(level)));
            return true;
        }

        EnchantDefinition definition = manager.definition(id);
        if (definition != null && manager.isSeasonal(id)) {
            int level = 1;
            if (args.length >= 3) {
                try {
                    level = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    player.sendMessage(messages.get(player, "enchant.invalid-level"));
                    return true;
                }
            }
            int maxLevel = definition.maxLevel();
            if (level < 1 || level > maxLevel) {
                player.sendMessage(messages.get(player, "seasonal.invalid-level-range",
                        "id", definition.displayName(), "max", String.valueOf(maxLevel),
                        "max-roman", RuneFormatting.roman(maxLevel)));
                return true;
            }
            int pieceAmount = 1;
            if (args.length >= 4) {
                try {
                    pieceAmount = Integer.parseInt(args[3]);
                    if (pieceAmount <= 0) {
                        player.sendMessage(messages.get(player, "seasonal.give-usage"));
                        return true;
                    }
                    pieceAmount = Math.min(pieceAmount, 64 * 6);
                } catch (NumberFormatException e) {
                    player.sendMessage(messages.get(player, "seasonal.give-usage"));
                    return true;
                }
            }
            // If this level's runes.yml entry configures catalog-item,
            // EnchantManager.createEnchantItem automatically bakes the
            // ability directly onto that saved catalog piece (the real
            // Fallen Helmet, not a generic placeholder icon) -- nothing
            // else to wire up here. See EnchantDefinition.Level#catalogItem.
            List<ItemStack> pieces = new ArrayList<>(pieceAmount);
            for (int i = 0; i < pieceAmount; i++) {
                pieces.add(manager.createEnchantItem(id, level, RuneTier.SEASONAL));
            }
            me.vertex.core.storage.ItemGiver.give(player, pieces);
            player.sendMessage(messages.get(player, "seasonal.given",
                    "amount", String.valueOf(pieces.size()), "id", definition.displayName()));
            return true;
        }

        ItemStack catalogued = catalog.get(id);
        if (catalogued == null) {
            player.sendMessage(messages.get(player, "seasonal.unknown-catalog-item", "id", id));
            return true;
        }
        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Integer.parseInt(args[2]);
                if (amount <= 0) {
                    player.sendMessage(messages.get(player, "seasonal.give-usage"));
                    return true;
                }
                amount = Math.min(amount, 64 * 6);
            } catch (NumberFormatException e) {
                player.sendMessage(messages.get(player, "seasonal.give-usage"));
                return true;
            }
        }
        List<ItemStack> items = new ArrayList<>(amount);
        for (int i = 0; i < amount; i++) {
            items.add(catalogued.clone());
        }
        me.vertex.core.storage.ItemGiver.give(player, items);
        player.sendMessage(messages.get(player, "seasonal.given", "amount", String.valueOf(items.size()), "id", id));
        return true;
    }

    /**
     * {@code /seasonal catalog save <id>} snapshots the sender's held item
     * -- enchantments, lore, custom model data, any other plugin's PDC data
     * -- exactly as it currently is, no re-derivation. {@code catalog
     * remove <id>} deletes an entry; {@code catalog list} shows every id
     * currently saved.
     */
    private boolean handleCatalog(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(messages.get(player, "seasonal.catalog-usage"));
            return true;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "save" -> {
                if (args.length < 3) {
                    player.sendMessage(messages.get(player, "seasonal.catalog-usage"));
                    return true;
                }
                String id = args[2].toLowerCase(Locale.ROOT);
                ItemStack held = player.getInventory().getItemInMainHand();
                if (held == null || held.getType().isAir()) {
                    player.sendMessage(messages.get(player, "seasonal.catalog-empty-hand"));
                    return true;
                }
                // Every seasonal enchant is configured for exactly one
                // compatible piece type (helmet-only, sword-only, ...), so
                // the held item's own material tells us which ability
                // belongs on it -- auto-bake it on before saving whenever
                // that's unambiguous. Zero or multiple matches (a non-Rune
                // item like the backpack, or overlapping compatible-types)
                // just saves the item as-is, same as before.
                List<EnchantDefinition> matches = new ArrayList<>();
                for (String enchantId : manager.seasonalIds()) {
                    EnchantDefinition candidate = manager.definition(enchantId);
                    if (candidate != null && candidate.isCompatible(held.getType())) {
                        matches.add(candidate);
                    }
                }
                if (matches.size() == 1) {
                    EnchantDefinition definition = matches.get(0);
                    int level = definition.maxLevel();
                    ItemStack baked = manager.bakeEnchantOnto(held.clone(), definition.id(), level, RuneTier.SEASONAL);
                    catalog.save(id, baked);
                    player.sendMessage(messages.get(player, "seasonal.catalog-saved-with-enchant", "id", id,
                            "enchant", RuneFormatting.coloredNameRaw(RuneTier.SEASONAL, definition.displayName()),
                            "level", String.valueOf(level)));
                    return true;
                }
                catalog.save(id, held);
                if (matches.isEmpty()) {
                    // Still succeeds -- this is the expected, ordinary case
                    // for a non-Rune catalog item (the backpack) -- but says
                    // so explicitly rather than staying silent, since it's
                    // also exactly what a material mismatch on an intended
                    // Rune piece looks like (nothing to compare the held
                    // item's actual Material against without this).
                    player.sendMessage(messages.get(player, "seasonal.catalog-saved-no-match", "id", id,
                            "material", held.getType().name()));
                } else {
                    String ids = matches.stream().map(EnchantDefinition::id).collect(Collectors.joining(", "));
                    player.sendMessage(messages.get(player, "seasonal.catalog-saved-ambiguous", "id", id,
                            "material", held.getType().name(), "matches", ids));
                }
            }
            case "remove" -> {
                if (args.length < 3) {
                    player.sendMessage(messages.get(player, "seasonal.catalog-usage"));
                    return true;
                }
                String id = args[2].toLowerCase(Locale.ROOT);
                if (catalog.remove(id)) {
                    player.sendMessage(messages.get(player, "seasonal.catalog-removed", "id", id));
                } else {
                    player.sendMessage(messages.get(player, "seasonal.unknown-catalog-item", "id", id));
                }
            }
            case "list" -> {
                String joined = String.join(", ", catalog.ids());
                player.sendMessage(messages.get(player, "seasonal.catalog-list",
                        "ids", joined.isEmpty() ? "none" : joined));
            }
            default -> player.sendMessage(messages.get(player, "seasonal.catalog-usage"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(EnchantCommand.GIVE_PERMISSION)) {
            return List.of();
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("item")) {
            if (args.length == 2) {
                String partial = args[1].toUpperCase(Locale.ROOT);
                return Arrays.stream(Material.values())
                        .filter(Material::isItem)
                        .map(Enum::name)
                        .filter(name -> name.startsWith(partial))
                        .limit(20)
                        .collect(Collectors.toList());
            }
            return List.of();
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("give")) {
            if (args.length == 2) {
                String partial = args[1].toLowerCase(Locale.ROOT);
                return Stream.of(catalog.ids().stream(), backpackManager.tierIds().stream(), manager.seasonalIds().stream())
                        .flatMap(s -> s)
                        .filter(id -> id.startsWith(partial))
                        .toList();
            }
            if (args.length == 3) {
                String id = args[1];
                EnchantDefinition definition = manager.definition(id);
                if (definition == null || !manager.isSeasonal(id)) {
                    return List.of();
                }
                int maxLevel = definition.maxLevel();
                String partial = args[2];
                List<String> levels = new ArrayList<>(maxLevel);
                for (int level = 1; level <= maxLevel; level++) {
                    String value = String.valueOf(level);
                    if (value.startsWith(partial)) {
                        levels.add(value);
                    }
                }
                return levels;
            }
            return List.of();
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("catalog")) {
            if (args.length == 2) {
                return Stream.of("save", "remove", "list")
                        .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
                        .toList();
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("remove")) {
                return catalog.ids().stream()
                        .filter(id -> id.startsWith(args[2].toLowerCase(Locale.ROOT)))
                        .toList();
            }
            return List.of();
        }
        return switch (args.length) {
            case 1 -> Stream.of("roll", "item", "give", "catalog")
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
            case 2 -> "item".startsWith(args[1].toLowerCase(Locale.ROOT)) ? List.of("item") : List.of();
            case 3 -> Stream.concat(manager.seasonalIds().stream(), catalog.ids().stream())
                    .filter(id -> id.startsWith(args[2].toLowerCase(Locale.ROOT)))
                    .toList();
            default -> List.of();
        };
    }
}
