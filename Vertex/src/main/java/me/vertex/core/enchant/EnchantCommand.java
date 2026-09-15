package me.vertex.core.enchant;

import me.vertex.core.enchant.menu.IncineratorConfirmMenu;
import me.vertex.core.enchant.menu.RuneShopMenu;
import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /enchant}, {@code /ce}, {@code /customenchants}, and {@code /runes}
 * open the dedicated Rune shop. {@code /enchant give <player> rune|gem
 * <tier> [amount]} remains the administrative distribution path.
 */
public final class EnchantCommand implements CommandExecutor, TabCompleter {

    public static final String GIVE_PERMISSION = "vertex.enchant.give";

    private final EnchantManager manager;
    private final RunePreferenceManager preferences;
    private final AutoIncineration autoIncineration;
    private final IncinerationService incineration;
    private final Messages messages;

    public EnchantCommand(EnchantManager manager, RunePreferenceManager preferences, AutoIncineration autoIncineration,
            IncinerationService incineration, Messages messages) {
        this.manager = manager;
        this.preferences = preferences;
        this.autoIncineration = autoIncineration;
        this.incineration = incineration;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(messages.get(sender, "general.players-only"));
                return true;
            }
            RuneShopMenu.open(player, manager, messages);
            return true;
        }
        if (args[0].equalsIgnoreCase("auto")) {
            return handleAuto(sender);
        }
        if (!sender.hasPermission(GIVE_PERMISSION)) {
            sender.sendMessage(messages.get(sender, "general.no-permission"));
            return true;
        }
        if (args.length < 3 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(messages.get(sender, "enchant.usage"));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(messages.get(sender, "general.player-not-found"));
            return true;
        }
        String kind = args[2].toLowerCase(Locale.ROOT);
        if (kind.equals("item")) {
            return handleGiveItem(sender, target, args);
        }
        if (kind.equals("seasonalset")) {
            return handleGiveSeasonalSet(sender, target, args);
        }
        if (!kind.equals("rune") && !kind.equals("gem")) {
            sender.sendMessage(messages.get(sender, "enchant.usage"));
            return true;
        }

        int amount = 1;
        RuneTier tier = null;
        if (kind.equals("rune")) {
            if (args.length < 4) {
                sender.sendMessage(messages.get(sender, "enchant.usage"));
                return true;
            }
            tier = parseTier(args[3]);
            if (tier == null) {
                sender.sendMessage(messages.get(sender, "rune.unknown-tier"));
                return true;
            }
            if (args.length >= 5) {
                amount = parseAmount(args[4], sender);
                if (amount <= 0) {
                    return true;
                }
            }
        } else if (args.length >= 4) {
            amount = parseAmount(args[3], sender);
            if (amount <= 0) {
                return true;
            }
        }

        List<ItemStack> items = new ArrayList<>(amount);
        for (int i = 0; i < amount; i++) {
            items.add(kind.equals("rune") ? manager.createRune(tier) : manager.createLuckyGem());
        }
        if (!me.vertex.core.storage.DeliveryManager.queueOverflow(
                manager.plugin(), target, items, "enchant-admin-give")) {
            sender.sendMessage(messages.get(sender, "delivery.storage-unavailable"));
            return true;
        }
        String kindLabel = kind.equals("rune") ? (tier.name() + " Rune") : "Lucky Gem";
        sender.sendMessage(messages.get(sender, "enchant.given", "player", target.getName(),
                "amount", String.valueOf(amount), "kind", kindLabel));
        return true;
    }

    /**
     * {@code /enchant give <player> item <enchant-id> <level> [amount]
     * [customModelData]} -- spawns an already-identified enchant item
     * directly (no roll, no identification step), for admin/event
     * distribution of seasonal content that has no roll-table weight and is
     * never obtained any other way. Restricted to {@code seasonal: true}
     * enchants specifically so this doesn't become a second, redundant way
     * to obtain the normal tiers' content outside their roll tables.
     *
     * <p>The optional trailing {@code customModelData} overrides whatever
     * model number that level is configured with in {@code runes.yml} --
     * for stamping this one give with a specific ItemsAdder custom-item
     * model on the fly, without editing config. {@code amount} must be
     * given (even the default {@code 1}) to reach it, since both are
     * positional integers.
     */
    private boolean handleGiveItem(CommandSender sender, Player target, String[] args) {
        if (args.length < 5) {
            sender.sendMessage(messages.get(sender, "enchant.usage"));
            return true;
        }
        String id = args[3];
        if (!manager.isSeasonal(id)) {
            sender.sendMessage(messages.get(sender, "enchant.unknown-seasonal-item", "id", id));
            return true;
        }
        int level;
        try {
            level = Integer.parseInt(args[4]);
            if (level <= 0) {
                sender.sendMessage(messages.get(sender, "enchant.invalid-level"));
                return true;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(messages.get(sender, "enchant.invalid-level"));
            return true;
        }
        int amount = 1;
        if (args.length >= 6) {
            amount = parseAmount(args[5], sender);
            if (amount <= 0) {
                return true;
            }
        }
        Integer customModelData = null;
        if (args.length >= 7) {
            try {
                customModelData = Integer.parseInt(args[6]);
            } catch (NumberFormatException e) {
                sender.sendMessage(messages.get(sender, "enchant.invalid-model-data"));
                return true;
            }
        }
        List<ItemStack> items = new ArrayList<>(amount);
        for (int i = 0; i < amount; i++) {
            ItemStack created = manager.createEnchantItem(id, level, RuneTier.SEASONAL);
            if (created == null) {
                sender.sendMessage(messages.get(sender, "enchant.unknown-seasonal-item", "id", id));
                return true;
            }
            if (customModelData != null) {
                applyCustomModelData(created, customModelData);
            }
            items.add(created);
        }
        if (!me.vertex.core.storage.DeliveryManager.queueOverflow(
                manager.plugin(), target, items, "enchant-admin-give-item")) {
            sender.sendMessage(messages.get(sender, "delivery.storage-unavailable"));
            return true;
        }
        String kindLabel = manager.definition(id).displayName() + " " + me.vertex.core.enchant.RuneFormatting.roman(level);
        sender.sendMessage(messages.get(sender, "enchant.given", "player", target.getName(),
                "amount", String.valueOf(amount), "kind", kindLabel));
        return true;
    }

    /**
     * {@code /enchant give <player> seasonalset [customModelData]} -- one
     * copy of <em>every level</em>, of <em>every</em> currently registered
     * seasonal enchant, in a single call: 5 levels each for the I-V armor/
     * weapon pieces, 10 each for the I-X farming tools. Assembling that by
     * hand would be eleven separate {@code give item} calls times each
     * item's own level count.
     */
    private boolean handleGiveSeasonalSet(CommandSender sender, Player target, String[] args) {
        Integer customModelData = null;
        if (args.length >= 4) {
            try {
                customModelData = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                sender.sendMessage(messages.get(sender, "enchant.invalid-model-data"));
                return true;
            }
        }
        List<ItemStack> items = new ArrayList<>();
        for (String id : manager.seasonalIds()) {
            EnchantDefinition definition = manager.definition(id);
            if (definition == null) {
                continue;
            }
            for (int level = 1; level <= definition.maxLevel(); level++) {
                ItemStack created = manager.createEnchantItem(id, level, RuneTier.SEASONAL);
                if (created == null) {
                    continue;
                }
                if (customModelData != null) {
                    applyCustomModelData(created, customModelData);
                }
                items.add(created);
            }
        }
        if (items.isEmpty()) {
            sender.sendMessage(messages.get(sender, "enchant.unknown-seasonal-item", "id", "seasonalset"));
            return true;
        }
        if (!me.vertex.core.storage.DeliveryManager.queueOverflow(
                manager.plugin(), target, items, "enchant-admin-give-seasonal-set")) {
            sender.sendMessage(messages.get(sender, "delivery.storage-unavailable"));
            return true;
        }
        sender.sendMessage(messages.get(sender, "enchant.given-seasonal-set", "player", target.getName(),
                "amount", String.valueOf(items.size())));
        return true;
    }

    private static void applyCustomModelData(ItemStack item, int customModelData) {
        var meta = item.getItemMeta();
        meta.setCustomModelData(customModelData);
        item.setItemMeta(meta);
    }

    private boolean handleAuto(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get(sender, "general.players-only"));
            return true;
        }
        if (!player.hasPermission(AutoIncineration.PERMISSION)) {
            player.sendMessage(messages.get(player, "general.no-permission"));
            return true;
        }
        if (autoIncineration.isEnabled(player.getUniqueId())) {
            // Disabling is immediate, no confirmation, and never touches saved filters.
            autoIncineration.setEnabled(player.getUniqueId(), false);
            player.sendMessage(messages.get(player, "rune.auto-disabled"));
            return true;
        }
        IncineratorConfirmMenu.openAutoEnable(player, messages);
        return true;
    }

    private int parseAmount(String raw, CommandSender sender) {
        try {
            int amount = Integer.parseInt(raw);
            if (amount <= 0) {
                sender.sendMessage(messages.get(sender, "enchant.usage"));
                return -1;
            }
            return Math.min(amount, 64 * 6);
        } catch (NumberFormatException e) {
            sender.sendMessage(messages.get(sender, "enchant.usage"));
            return -1;
        }
    }

    private RuneTier parseTier(String raw) {
        try {
            return RuneTier.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(GIVE_PERMISSION)) {
            return List.of();
        }
        return switch (args.length) {
            case 1 -> "give".startsWith(args[0].toLowerCase(Locale.ROOT)) ? List.of("give") : List.of();
            case 2 -> Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .sorted()
                    .toList();
            case 3 -> List.of("rune", "gem", "item", "seasonalset").stream()
                    .filter(value -> value.startsWith(args[2].toLowerCase(Locale.ROOT)))
                    .toList();
            case 4 -> {
                if (args[2].equalsIgnoreCase("rune")) {
                    yield java.util.Arrays.stream(RuneTier.values())
                            .map(tier -> tier.name().toLowerCase(Locale.ROOT))
                            .filter(name -> name.startsWith(args[3].toLowerCase(Locale.ROOT)))
                            .toList();
                }
                if (args[2].equalsIgnoreCase("item")) {
                    yield manager.seasonalIds().stream()
                            .filter(id -> id.startsWith(args[3].toLowerCase(Locale.ROOT)))
                            .toList();
                }
                yield List.of();
            }
            case 5 -> args[2].equalsIgnoreCase("item") ? List.of("1", "2", "3", "4", "5") : List.of();
            default -> List.of();
        };
    }
}
