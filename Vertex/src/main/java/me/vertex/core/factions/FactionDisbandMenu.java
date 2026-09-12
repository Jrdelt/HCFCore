package me.vertex.core.factions;

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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/** Two-step, close-to-cancel confirmation for normal faction disbands. */
public final class FactionDisbandMenu implements Listener {
    private final Plugin plugin;
    private final FactionService factions;
    private final Messages messages;
    private final Predicate<java.util.UUID> combatTagged;
    private final NamespacedKey actionKey;

    public FactionDisbandMenu(Plugin plugin, FactionService factions, Messages messages,
            Predicate<java.util.UUID> combatTagged) {
        this.plugin = plugin;
        this.factions = factions;
        this.messages = messages;
        this.combatTagged = combatTagged;
        this.actionKey = new NamespacedKey(plugin, "faction_disband_action");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String[] parts = event.getMessage().substring(1).trim().split("\\s+");
        if (parts.length != 2 || !isFactionCommand(parts[0]) || !parts[1].equalsIgnoreCase("disband")) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        FactionMember member = factions.member(player.getUniqueId());
        if (member == null) {
            player.sendMessage(messages.get(player, "native-factions.result.no-faction"));
            return;
        }
        if (member.role() != FactionRole.LEADER) {
            player.sendMessage(messages.get(player, "native-factions.result.not-leader"));
            return;
        }
        if (combatTagged.test(player.getUniqueId())) {
            player.sendMessage(messages.get(player, "faction-disband.combat-blocked"));
            return;
        }
        open(player, member.factionId(), 1);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() != event.getView().getTopInventory()) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        String action = clicked.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) return;
        if (action.equals("cancel")) {
            player.closeInventory();
            player.sendMessage(messages.get(player, "faction-disband.cancelled"));
            return;
        }
        FactionMember member = factions.member(player.getUniqueId());
        if (member == null || member.factionId() != holder.factionId || member.role() != FactionRole.LEADER) {
            player.closeInventory();
            player.sendMessage(messages.get(player, "faction-disband.changed"));
            return;
        }
        if (combatTagged.test(player.getUniqueId())) {
            player.closeInventory();
            player.sendMessage(messages.get(player, "faction-disband.combat-blocked"));
            return;
        }
        if (holder.stage == 1) {
            open(player, holder.factionId, 2);
            return;
        }
        player.closeInventory();
        factions.submitMutation(() -> {
            if (combatTagged.test(player.getUniqueId())) return FactionService.Result.NO_PERMISSION;
            return factions.disband(player);
        }).whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (error != null || result == FactionService.Result.DATABASE_ERROR) {
                player.sendMessage(messages.get(player, "native-factions.result.database-error"));
            } else if (result == FactionService.Result.NO_PERMISSION) {
                player.sendMessage(messages.get(player, "faction-disband.combat-blocked"));
            } else if (result == FactionService.Result.OK) {
                player.sendMessage(messages.get(player, "native-factions.disbanded"));
            } else {
                player.sendMessage(messages.get(player, "faction-disband.changed"));
            }
        }));
    }

    private void open(Player player, int factionId, int stage) {
        Holder holder = new Holder(factionId, stage);
        Inventory inventory = Bukkit.createInventory(holder, 27,
                messages.getGui(player, stage == 1 ? "faction-disband.stage-one-title"
                        : "faction-disband.stage-two-title"));
        holder.inventory = inventory;
        ItemStack filler = item(player, Material.BLACK_STAINED_GLASS_PANE,
                "faction-disband.filler", List.of(), null);
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler);
        inventory.setItem(11, item(player, Material.LIME_STAINED_GLASS_PANE,
                stage == 1 ? "faction-disband.continue" : "faction-disband.final-confirm",
                messages.getGuiList(player, stage == 1 ? "faction-disband.continue-lore"
                        : "faction-disband.final-confirm-lore"), "confirm"));
        inventory.setItem(15, item(player, Material.RED_STAINED_GLASS_PANE,
                "faction-disband.cancel", messages.getGuiList(player, "faction-disband.cancel-lore"), "cancel"));
        player.openInventory(inventory);
    }

    private ItemStack item(Player player, Material material, String name,
            List<net.kyori.adventure.text.Component> lore, String action) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.getGui(player, name));
        if (!lore.isEmpty()) meta.lore(lore);
        if (action != null) meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isFactionCommand(String raw) {
        String command = raw.contains(":") ? raw.substring(raw.indexOf(':') + 1) : raw;
        String normalized = command.toLowerCase(Locale.ROOT);
        return plugin.getConfig().getStringList("factions.command-aliases").stream()
                .anyMatch(alias -> alias.equalsIgnoreCase(normalized));
    }

    private static final class Holder implements InventoryHolder {
        private final int factionId;
        private final int stage;
        private Inventory inventory;
        private Holder(int factionId, int stage) { this.factionId = factionId; this.stage = stage; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
