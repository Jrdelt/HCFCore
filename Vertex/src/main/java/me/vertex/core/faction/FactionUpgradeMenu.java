package me.vertex.core.faction;

import dev.kitteh.factions.Faction;
import dev.kitteh.factions.permissible.PermissibleActions;
import me.vertex.core.economy.EconomyHook;
import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.TabCompleteEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Routes /f upgrades and provides the faction-owned upgrade purchase GUI. */
public final class FactionUpgradeMenu implements Listener {
    private static final int INVENTORY_SIZE = 54;
    /**
     * Matches the framed double-chest layout: four evenly-spaced upgrades on
     * the first content row and five staggered upgrades on the row beneath.
     */
    private static final List<Integer> UPGRADE_SLOTS = List.of(10, 12, 14, 16, 20, 22, 24, 28, 30, 32, 34, 39, 41);

    private final Plugin plugin;
    private final FactionUpgradeManager manager;
    private final Messages messages;
    private final NamespacedKey upgradeKey;

    public FactionUpgradeMenu(Plugin plugin, FactionUpgradeManager manager, Messages messages) {
        this.plugin = plugin;
        this.manager = manager;
        this.messages = messages;
        this.upgradeKey = new NamespacedKey(plugin, "faction_upgrade");
    }

    public void open(Player player) {
        Faction faction = FactionsHook.getFaction(player);
        if (faction == null) {
            player.sendMessage(messages.get(player, "faction-upgrades.no-faction"));
            return;
        }
        if (!canUseUpgrades(player, faction)) {
            player.sendMessage(messages.get(player, "faction-upgrades.role-permission-denied"));
            return;
        }
        manager.adoptNativeWarpLevel(faction);
        if (!manager.isEnabled()) {
            player.sendMessage(messages.get(player, "faction-upgrades.disabled"));
            return;
        }

        Holder holder = new Holder(faction.id());
        Inventory inventory = Bukkit.createInventory(holder, INVENTORY_SIZE,
                messages.get(player, "faction-upgrades.gui-title"));
        holder.inventory = inventory;
        ItemStack border = border();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (!UPGRADE_SLOTS.contains(slot)) {
                inventory.setItem(slot, border);
            }
        }
        FactionUpgrade[] upgrades = FactionUpgrade.values();
        for (int index = 0; index < upgrades.length; index++) {
            inventory.setItem(UPGRADE_SLOTS.get(index), icon(player, faction, upgrades[index]));
        }
        player.openInventory(inventory);
    }

    /** FactionsUUID owns /f, so Vertex routes only the dedicated upgrades aliases. */
    // Run before FactionsUUID's own command bridge. Vertex owns this GUI;
    // FactionsUUID remains the source of truth only for its native Warps
    // upgrade, which FactionUpgradeManager synchronizes on every view/buy.
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFactionUpgradeCommand(PlayerCommandPreprocessEvent event) {
        String[] parts = event.getMessage().trim().toLowerCase(Locale.ROOT).split("\\s+");
        if (parts.length != 2 || !isFactionCommand(parts[0])
                || !(parts[1].equals("upgrades") || parts[1].equals("upgrade"))) {
            return;
        }
        event.setCancelled(true);
        open(event.getPlayer());
    }

    @EventHandler
    public void onFactionTabComplete(TabCompleteEvent event) {
        if (!(event.getSender() instanceof Player) || !event.getBuffer().startsWith("/")) {
            return;
        }
        String[] parts = event.getBuffer().substring(1).split("\\s+", -1);
        if (parts.length != 2 || !isFactionCommand(parts[0])) {
            return;
        }
        String partial = parts[1].toLowerCase(Locale.ROOT);
        List<String> completions = new ArrayList<>(event.getCompletions());
        for (String value : List.of("upgrade", "upgrades")) {
            if (value.startsWith(partial) && completions.stream().noneMatch(value::equalsIgnoreCase)) {
                completions.add(value);
            }
        }
        event.setCompletions(completions);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Faction faction = FactionsHook.getFaction(player);
        if (faction == null || faction.id() != holder.factionId) {
            player.closeInventory();
            return;
        }
        if (!canUseUpgrades(player, faction)) {
            player.sendMessage(messages.get(player, "faction-upgrades.role-permission-denied"));
            player.closeInventory();
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) {
            return;
        }
        String value = clicked.getItemMeta().getPersistentDataContainer().get(upgradeKey, PersistentDataType.STRING);
        FactionUpgrade upgrade = FactionUpgrade.fromConfigKey(value);
        if (upgrade == null) {
            return;
        }
        FactionUpgradeManager.PurchaseResult result = manager.purchase(player, faction, upgrade);
        switch (result) {
            case SUCCESS -> player.sendMessage(messages.get(player, "faction-upgrades.purchased",
                    "upgrade", upgradeName(player, upgrade), "level", String.valueOf(manager.level(faction.id(), upgrade))));
            case PENDING -> player.sendMessage(messages.get(player, "faction-upgrades.purchase-pending"));
            case LEADER_ONLY -> player.sendMessage(messages.get(player, "faction-upgrades.leader-only"));
            case MAXED -> player.sendMessage(messages.get(player, "faction-upgrades.maxed"));
            case NO_ECONOMY -> player.sendMessage(messages.get(player, "faction-upgrades.no-economy"));
            case CANNOT_AFFORD -> player.sendMessage(messages.get(player, "faction-upgrades.cannot-afford",
                    "amount", EconomyHook.format(manager.nextCost(faction.id(), upgrade))));
            case DISABLED -> player.sendMessage(messages.get(player, "faction-upgrades.disabled"));
        }
        // Reopen rather than closing the menu: a leader can purchase several
        // levels in succession, and the lore always reflects the live state.
        open(player);
    }

    private ItemStack icon(Player player, Faction faction, FactionUpgrade upgrade) {
        FactionUpgradeManager.Definition definition = manager.definition(upgrade);
        int level = manager.level(faction.id(), upgrade);
        ItemStack item = new ItemStack(definition.enabled() ? upgrade.icon() : Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.get(player, "faction-upgrades.gui.name." + upgrade.configKey()));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        String state = !definition.enabled() ? "disabled"
                : level >= definition.maxLevel() ? "maxed"
                : manager.leaderOnly() && !FactionsHook.isLeader(player) ? "leader-only" : "available";
        double nextCost = definition.enabled() && level < definition.maxLevel()
                ? manager.nextCost(faction.id(), upgrade) : 0D;
        List<net.kyori.adventure.text.Component> lore = messages.getList(player,
                "faction-upgrades.gui.lore." + state,
                "level", String.valueOf(level),
                "max", String.valueOf(definition.maxLevel()),
                "current", effect(player, upgrade, level),
                "next", effect(player, upgrade, Math.min(level + 1, definition.maxLevel())),
                "cost", EconomyHook.format(nextCost));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(upgradeKey, PersistentDataType.STRING, upgrade.configKey());
        item.setItemMeta(meta);
        return item;
    }

    /** The native UPGRADE switch in /f permissions also controls this GUI. */
    private static boolean canUseUpgrades(Player player, Faction faction) {
        return RallyPermissionMenu.isNativeActionAllowed(
                faction, RallyManager.roleId(player), PermissibleActions.UPGRADE);
    }

    private String upgradeName(Player player, FactionUpgrade upgrade) {
        return me.vertex.core.lang.MessageFormatter.plain(
                messages.getRaw(player, "faction-upgrades.gui.name." + upgrade.configKey()));
    }

    private String effect(Player player, FactionUpgrade upgrade, int level) {
        String bonus = number(manager.bonusAtLevel(upgrade, level));
        return messages.getRaw(player, "faction-upgrades.gui.effect." + upgrade.configKey(),
                "bonus", bonus, "level", String.valueOf(level));
    }

    private static String number(double value) {
        return Math.rint(value) == value ? String.valueOf((long) value)
                : String.format(Locale.ROOT, "%.1f", value);
    }

    private ItemStack border() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.empty());
        item.setItemMeta(meta);
        return item;
    }

    private boolean isFactionCommand(String rawCommand) {
        String command = rawCommand.startsWith("/") ? rawCommand.substring(1) : rawCommand;
        int namespace = command.indexOf(':');
        if (namespace >= 0) {
            command = command.substring(namespace + 1);
        }
        String normalized = command.toLowerCase(Locale.ROOT);
        return plugin.getConfig().getStringList("factions.command-aliases").stream()
                .map(alias -> alias.toLowerCase(Locale.ROOT)).anyMatch(normalized::equals);
    }

    private static final class Holder implements InventoryHolder {
        private final int factionId;
        private Inventory inventory;

        private Holder(int factionId) {
            this.factionId = factionId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
