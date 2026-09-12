package me.vertex.core.factions;

import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Configurable, persistent personal F Power Booster vouchers. */
public final class FPowerBooster implements CommandExecutor, TabCompleter, Listener {
    private final Plugin plugin;
    private final FactionService factions;
    private final Messages messages;
    private final NamespacedKey amountKey;
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private volatile Material material;
    private volatile Integer customModelData;
    private volatile boolean glowing;
    private volatile List<Integer> tiers = List.of(10, 25, 50, 75, 100);

    public FPowerBooster(Plugin plugin, FactionService factions, Messages messages) {
        this.plugin = plugin;
        this.factions = factions;
        this.messages = messages;
        this.amountKey = new NamespacedKey(plugin, "f_power_booster");
        reload();
    }

    public void reload() {
        String rawMaterial = plugin.getConfig().getString("factions.power.booster.material", "NETHER_STAR");
        material = Material.matchMaterial(rawMaterial == null ? "" : rawMaterial);
        if (material == null || material.isAir()) material = Material.NETHER_STAR;
        int model = plugin.getConfig().getInt("factions.power.booster.custom-model-data", 0);
        customModelData = model > 0 ? model : null;
        glowing = plugin.getConfig().getBoolean("factions.power.booster.glowing", true);
        List<Integer> configured = plugin.getConfig().getIntegerList("factions.power.booster.tiers").stream()
                .filter(value -> value > 0).distinct().sorted().toList();
        tiers = configured.isEmpty() ? List.of(10, 25, 50, 75, 100) : configured;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("vertex.fpowerbooster.give")) {
            sender.sendMessage(messages.get(sender, "general.no-permission"));
            return true;
        }
        if (args.length < 3 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(messages.get(sender, "fpowerbooster.usage"));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(messages.get(sender, "general.player-not-found"));
            return true;
        }
        int tier;
        int count = 1;
        try {
            tier = Integer.parseInt(args[2].replace("+", ""));
            if (args.length >= 4) count = Integer.parseInt(args[3]);
        } catch (NumberFormatException error) {
            sender.sendMessage(messages.get(sender, "fpowerbooster.usage"));
            return true;
        }
        if (!tiers.contains(tier) || count < 1 || count > 2_304) {
            sender.sendMessage(messages.get(sender, "fpowerbooster.invalid-tier",
                    "tiers", tiers.toString()));
            return true;
        }
        List<ItemStack> items = new ArrayList<>();
        int remaining = count;
        while (remaining > 0) {
            int stackSize = Math.min(material.getMaxStackSize(), remaining);
            items.add(create(tier, stackSize));
            remaining -= stackSize;
        }
        if (!me.vertex.core.storage.DeliveryManager.queueOverflow(
                plugin, target, items, "f-power-booster-admin-give")) {
            sender.sendMessage(messages.get(sender, "delivery.storage-unavailable"));
            return true;
        }
        sender.sendMessage(messages.get(sender, "fpowerbooster.given", "player", target.getName(),
                "tier", String.valueOf(tier), "amount", String.valueOf(count)));
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onRedeem(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack held = event.getItem();
        Integer tier = tier(held);
        if (tier == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!pending.add(player.getUniqueId())) return;
        // Escrow the voucher on the primary thread before the durable power
        // mutation. A player cannot move/drop the item while SQL is running
        // and receive permanent power without consuming it.
        ItemStack current = player.getInventory().getItemInMainHand();
        if (!tier.equals(tier(current))) {
            pending.remove(player.getUniqueId());
            return;
        }
        current.setAmount(current.getAmount() - 1);
        ItemStack refund = create(tier, 1);
        factions.submitMutation(() -> factions.increaseMaximumPower(player.getUniqueId(), tier))
                .whenComplete((applied, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    pending.remove(player.getUniqueId());
                    if (error != null) {
                        refund(player, refund);
                        player.sendMessage(messages.get(player, "fpowerbooster.save-failed"));
                        return;
                    }
                    if (applied == null || applied <= 0D) {
                        refund(player, refund);
                        player.sendMessage(messages.get(player, "fpowerbooster.at-cap"));
                        return;
                    }
                    player.sendMessage(messages.get(player, "fpowerbooster.redeemed", "amount", number(applied),
                            "maximum", number(factions.powerProfile(player.getUniqueId()).maximum())));
                }));
    }

    private void refund(Player player, ItemStack refund) {
        if (me.vertex.core.storage.DeliveryManager.queueOverflow(
                plugin, player, List.of(refund), "f-power-booster-refund")) return;

        // Both durable admission paths are unavailable. Put the one voucher
        // directly back into the stack it came from whenever possible; the
        // source operation removed exactly one item moments earlier.
        ItemStack held = player.getInventory().getItemInMainHand();
        Integer refundTier = tier(refund);
        if (refundTier != null && refundTier.equals(tier(held))
                && held.getAmount() < held.getMaxStackSize()) {
            held.setAmount(held.getAmount() + 1);
            player.updateInventory();
            return;
        }
        if (player.getInventory().addItem(refund).isEmpty()) {
            player.updateInventory();
            return;
        }
        plugin.getLogger().severe("Could not restore an F Power Booster to " + player.getUniqueId()
                + " after both delivery storage and immediate inventory restoration failed.");
        player.sendMessage(messages.get(player, "delivery.storage-unavailable"));
    }

    ItemStack create(int tier, int count) {
        ItemStack item = new ItemStack(material, count);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.get(null, "fpowerbooster.item-name", "amount", String.valueOf(tier)));
        meta.lore(messages.getList(null, "fpowerbooster.item-lore", "amount", String.valueOf(tier)));
        meta.getPersistentDataContainer().set(amountKey, PersistentDataType.INTEGER, tier);
        if (customModelData != null) meta.setCustomModelData(customModelData);
        meta.setEnchantmentGlintOverride(glowing);
        item.setItemMeta(meta);
        return item;
    }

    private Integer tier(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        Integer value = item.getItemMeta().getPersistentDataContainer().get(amountKey, PersistentDataType.INTEGER);
        return value != null && tiers.contains(value) ? value : null;
    }

    private static String number(double value) {
        return Math.rint(value) == value ? String.format(Locale.ROOT, "%.0f", value)
                : String.format(Locale.ROOT, "%.2f", value);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("vertex.fpowerbooster.give")) return List.of();
        List<String> options = switch (args.length) {
            case 1 -> List.of("give");
            case 2 -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            case 3 -> tiers.stream().map(String::valueOf).toList();
            case 4 -> List.of("1", "8", "16", "64");
            default -> List.of();
        };
        String partial = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(partial)).toList();
    }
}
