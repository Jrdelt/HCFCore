package me.vertex.core.zone;

import me.vertex.core.lang.MessageFormatter;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** Config-backed entry/progress/loot menus. Holders make every click route explicit and stale-safe. */
public final class ZoneMenu implements Listener {
    private final ZoneManager zones;

    public ZoneMenu(ZoneManager zones) { this.zones = zones; }

    public void openEntry(Player player, ZoneType type) {
        ZoneManager.ZoneConfig config = zones.config(type);
        Inventory inventory = Bukkit.createInventory(new Holder(Kind.ENTRY, type, false), config.entrySize(), MessageFormatter.deserialize(config.entryTitle()));
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler);
        ItemStack confirm = item(config.entryMaterial(), config.entryName(), config.entryLore());
        ItemMeta meta = confirm.getItemMeta();
        if (config.entryModel() != null) meta.setCustomModelData(config.entryModel());
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS); confirm.setItemMeta(meta);
        inventory.setItem(inventory.getSize() / 2, confirm);
        player.openInventory(inventory);
    }

    public void openProgress(Player player) {
        Inventory inventory = Bukkit.createInventory(new Holder(Kind.PROGRESS, null, false), 27, MessageFormatter.deserialize("<dark_gray>Zone Progress"));
        inventory.setItem(11, progressItem(player, ZoneType.HAVEN, Material.EMERALD));
        inventory.setItem(15, progressItem(player, ZoneType.RIFTLANDS, Material.CRYING_OBSIDIAN));
        inventory.setItem(13, eventItem(player));
        player.openInventory(inventory);
    }

    public void openLoot(Player player, ZoneType type, boolean admin) {
        List<ZoneManager.LootEntry> entries = zones.loot(type);
        int size = Math.max(27, Math.min(54, ((entries.size() + 8) / 9 + 2) * 9));
        Inventory inventory = Bukkit.createInventory(new Holder(Kind.LOOT, type, admin), size,
                MessageFormatter.deserialize((admin ? "<gold>Edit " : "<dark_gray>") + type.displayName() + " Loot"));
        for (int slot = 0; slot < entries.size() && slot < size - 9; slot++) inventory.setItem(slot, displayLoot(player, type, entries.get(slot)));
        ItemStack information = item(admin ? Material.LIME_DYE : Material.BOOK,
                admin ? "<green>Loot Pool Editor" : "<aqua>Loot Information",
                admin ? List.of("<gray>Move real items in the top rows.", "<gray>Right-click an item to raise its chance.", "<gray>Shift-right-click lowers it. Close to save.")
                        : List.of("<gray>Items are read-only.", "<gray>Amplification grants extra rolls; it never changes base rarity."));
        inventory.setItem(size - 5, information);
        player.openInventory(inventory);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) { event.setCancelled(true); return; }
        if (holder.kind == Kind.ENTRY) {
            event.setCancelled(true);
            if (event.getRawSlot() == event.getInventory().getSize() / 2) {
                player.closeInventory();
                String result = zones.beginEntry(player, holder.type);
                if (!"ok".equals(result)) {
                    String key = result.contains("cooldown") ? "zones.entry-cooldown"
                            : result.equals("combat") ? "zones.entry-combat" : "zones.no-route";
                    player.sendMessage(zones.message(player, key));
                }
            }
            return;
        }
        if (holder.kind == Kind.PROGRESS) { event.setCancelled(true); return; }
        if (holder.kind != Kind.LOOT) return;
        if (!holder.admin) { event.setCancelled(true); return; }
        if (event.getRawSlot() >= event.getInventory().getSize() - 9) { event.setCancelled(true); return; }
        if (event.getCurrentItem() != null && !event.getCurrentItem().isEmpty() && event.isRightClick()) {
            event.setCancelled(true);
            ItemStack item = event.getCurrentItem();
            double current = zones.lootChance(item);
            zones.setLootChance(item, current + (event.isShiftClick() ? -1D : 1D));
            event.getInventory().setItem(event.getRawSlot(), displayLoot(player, holder.type, new ZoneManager.LootEntry(item, zones.lootChance(item))));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder holder && (!holder.admin || holder.kind != Kind.LOOT)) event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder) || holder.kind != Kind.LOOT || !holder.admin) return;
        List<ZoneManager.LootEntry> entries = new ArrayList<>();
        int editable = event.getInventory().getSize() - 9;
        for (int slot = 0; slot < editable; slot++) {
            ItemStack item = event.getInventory().getItem(slot);
            if (item == null || item.isEmpty()) continue;
            ItemStack copy = item.clone(); zones.normalizeLootItem(holder.type, copy);
            entries.add(new ZoneManager.LootEntry(copy, zones.lootChance(copy)));
        }
        zones.saveLoot(holder.type, entries);
        if (event.getPlayer() instanceof Player player) player.sendMessage(zones.message(player, "zones.loot-saved"));
    }

    private ItemStack progressItem(Player player, ZoneType type, Material material) {
        long kills = zones.kills(player, type); long next = zones.nextMilestone(player, type);
        List<String> lore = new ArrayList<>(); lore.add("<gray>Seasonal kills: <white>" + kills); lore.add("<gray>Amplification: <green>+" + zones.progressionBoost(player, type) + "%");
        lore.add(next == 0 ? "<gray>Next milestone: <green>Maximum reached" : "<gray>Next milestone: <white>" + next + " <dark_gray>(" + Math.max(0, next - kills) + " left)");
        return item(material, "<" + (type == ZoneType.HAVEN ? "green" : "light_purple") + ">" + type.displayName(), lore);
    }
    private ItemStack eventItem(Player player) {
        List<ZoneManager.Score> top = zones.topScoresForDisplay(); List<String> lore = new ArrayList<>();
        lore.add("<gray>" + (zones.eventRemainingSeconds() > 0 ? "Time: <white>" + zones.eventRemainingSeconds() + "s" : "No active event"));
        for (int i=0;i<top.size();i++) lore.add("<gray>#"+(i+1)+" <white>"+top.get(i).name()+": <aqua>"+top.get(i).score());
        lore.add("<gray>Your winner boost: <green>+" + zones.winnerBoost(player) + "%");
        return item(Material.NETHER_STAR,"<gold>Mob Kill Event",lore);
    }
    private ItemStack displayLoot(Player player, ZoneType type, ZoneManager.LootEntry entry) {
        ItemStack copy = entry.item().clone(); ItemMeta meta = copy.getItemMeta(); List<net.kyori.adventure.text.Component> lore = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
        lore.add(Component.empty()); lore.add(MessageFormatter.deserialize("<gray>Base Drop Chance: <yellow>" + entry.chance() + "%"));
        lore.add(MessageFormatter.deserialize("<gray>Mob Drop Amplification: <green>+" + zones.amplification(player, type) + "%"));
        if (entry.chance() < zones.config(type).rareThreshold()) lore.add(MessageFormatter.deserialize("<red>Rare protection: max 1 per kill"));
        lore.add(MessageFormatter.deserialize("<dark_gray>Extra rolls use the original rarity.")); meta.lore(lore); copy.setItemMeta(meta); return copy;
    }
    private ItemStack item(Material material, String name, List<String> lore) { ItemStack item = new ItemStack(material); ItemMeta meta=item.getItemMeta();meta.displayName(MessageFormatter.deserialize(name));meta.lore(lore.stream().map(MessageFormatter::deserialize).toList());item.setItemMeta(meta);return item; }
    private enum Kind { ENTRY, PROGRESS, LOOT }
    private record Holder(Kind kind, ZoneType type, boolean admin) implements InventoryHolder { @Override public Inventory getInventory() { return null; } }
}
