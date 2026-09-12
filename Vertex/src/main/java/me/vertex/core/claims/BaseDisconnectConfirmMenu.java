package me.vertex.core.claims;

import me.vertex.core.factions.FactionMember;
import me.vertex.core.factions.FactionService;
import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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
import org.bukkit.plugin.Plugin;

import java.util.List;

/** Warns before an unclaim splits a Base graph and converts separated land to Raid Claims. */
public final class BaseDisconnectConfirmMenu implements Listener {
    private final Plugin plugin;
    private final FactionService factions;
    private final BaseClaimManager bases;
    private final Messages messages;

    public BaseDisconnectConfirmMenu(Plugin plugin, FactionService factions,
            BaseClaimManager bases, Messages messages) {
        this.plugin = plugin; this.factions = factions; this.bases = bases; this.messages = messages;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String[] parts = event.getMessage().substring(1).trim().split("\\s+");
        if (parts.length != 2 || !isFactionRoot(parts[0]) || !parts[1].equalsIgnoreCase("unclaim")) return;
        Player player = event.getPlayer();
        int factionId = FactionsHook.getFactionId(player);
        ChunkKey chunk = ChunkKey.of(player.getLocation());
        if (factionId == FactionsHook.NO_FACTION || !bases.wouldDisconnectOnUnclaim(factionId, chunk)) return;
        event.setCancelled(true);
        Holder holder = new Holder(factionId, chunk);
        Inventory inventory = Bukkit.createInventory(holder, 27,
                messages.getGui(player, "baseclaim.disconnect-title"));
        holder.inventory = inventory;
        ItemStack filler = item(player, Material.BLACK_STAINED_GLASS_PANE, "baseclaim.gui.filler", List.of());
        for (int slot = 0; slot < 27; slot++) inventory.setItem(slot, filler);
        inventory.setItem(11, item(player, Material.ORANGE_STAINED_GLASS_PANE,
                "baseclaim.disconnect-confirm", messages.getGuiList(player, "baseclaim.disconnect-lore")));
        inventory.setItem(15, item(player, Material.RED_STAINED_GLASS_PANE,
                "baseclaim.gui.cancel-name", messages.getGuiList(player, "baseclaim.gui.cancel-lore")));
        player.openInventory(inventory);
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
        if (event.getSlot() == 15) {
            player.closeInventory();
            player.sendMessage(messages.get(player, "baseclaim.cancelled"));
            return;
        }
        if (event.getSlot() != 11) return;
        FactionMember member = factions.member(player.getUniqueId());
        if (member == null || member.factionId() != holder.factionId
                || factions.factionIdAt(holder.chunk) != holder.factionId) {
            player.closeInventory();
            player.sendMessage(messages.get(player, "baseclaim.changed"));
            return;
        }
        player.closeInventory();
        factions.submitMutation(() -> factions.unclaim(player, holder.chunk)).whenComplete((result, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    if (error != null || result == FactionService.Result.DATABASE_ERROR) {
                        player.sendMessage(messages.get(player, "native-factions.result.database-error"));
                    } else if (result == FactionService.Result.OK) {
                        player.sendMessage(messages.get(player, "baseclaim.disconnected"));
                    } else {
                        player.sendMessage(messages.get(player, "native-factions.result."
                                + result.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-')));
                    }
                }));
    }

    private ItemStack item(Player player, Material material, String key,
            List<net.kyori.adventure.text.Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.getGui(player, key));
        if (!lore.isEmpty()) meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isFactionRoot(String raw) {
        String root = raw.contains(":") ? raw.substring(raw.indexOf(':') + 1) : raw;
        return plugin.getConfig().getStringList("factions.command-aliases").stream()
                .anyMatch(root::equalsIgnoreCase);
    }

    private static final class Holder implements InventoryHolder {
        private final int factionId;
        private final ChunkKey chunk;
        private Inventory inventory;
        private Holder(int factionId, ChunkKey chunk) { this.factionId = factionId; this.chunk = chunk; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
