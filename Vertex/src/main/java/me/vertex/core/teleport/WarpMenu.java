package me.vertex.core.teleport;

import me.vertex.core.lang.Messages;
import me.vertex.core.network.NetworkStorage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Double-chest public warp browser. */
public final class WarpMenu implements Listener {
    private static final List<Integer> CONTENT = List.of(10,11,12,13,14,15,16,19,20,21,22,23,24,25,
            28,29,30,31,32,33,34,37,38,39,40,41,42,43);
    private final GlobalLocationManager locations;
    private final TeleportManager teleports;
    private final Messages messages;
    private final int countdown;

    public WarpMenu(GlobalLocationManager locations, TeleportManager teleports, Messages messages, int countdown) {
        this.locations = locations; this.teleports = teleports; this.messages = messages; this.countdown = countdown;
    }

    public void open(Player player) {
        Holder holder = new Holder();
        Inventory inventory = Bukkit.createInventory(holder, 54, messages.getGui(player, "warps.gui.title"));
        holder.inventory = inventory;
        ItemStack filler = item(player, Material.BLACK_STAINED_GLASS_PANE, "warps.gui.filler", List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler);
        List<NetworkStorage.LocationRow> rows = locations.warps();
        for (int index = 0; index < Math.min(rows.size(), CONTENT.size()); index++) {
            NetworkStorage.LocationRow row = rows.get(index);
            int slot = CONTENT.get(index);
            holder.names.put(slot, row.name());
            inventory.setItem(slot, item(player, Material.ENDER_PEARL, "warps.gui.entry-name",
                    messages.getGuiList(player, "warps.gui.entry-lore", "name", row.name(),
                            "description", row.description() == null ? "" : row.description(),
                            "shard", row.location().shardId(), "world", row.location().world())));
        }
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0
                || event.getRawSlot() >= event.getInventory().getSize()) return;
        String name = holder.names.get(event.getRawSlot());
        if (name == null) return;
        player.closeInventory();
        teleports.request(player, "warp", () -> locations.warpTarget(name), countdown, 0L, true);
    }

    private ItemStack item(Player player, Material material, String name, List<net.kyori.adventure.text.Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.getGui(player, name));
        if (!lore.isEmpty()) meta.lore(new ArrayList<>(lore));
        item.setItemMeta(meta);
        return item;
    }

    private static final class Holder implements InventoryHolder {
        private final Map<Integer,String> names = new LinkedHashMap<>();
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }
}
