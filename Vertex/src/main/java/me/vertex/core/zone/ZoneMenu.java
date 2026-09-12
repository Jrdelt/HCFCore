package me.vertex.core.zone;

import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.lang.Messages;
import me.vertex.core.lang.SmallCaps;
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
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/** Config-backed entry/progress/loot menus. Holders make every click route explicit and stale-safe. */
public final class ZoneMenu implements Listener {
    private static final int LOOT_MENU_SIZE = 54;
    private static final int EDITABLE_LOOT_SLOTS = 45;
    private static final int LOOT_PREVIOUS_SLOT = 45;
    private static final int LOOT_INFO_SLOT = 48;
    private static final int LOOT_BACK_SLOT = 49;
    private static final int LOOT_NEXT_SLOT = 53;
    private final Plugin plugin;
    private final ZoneManager zones;
    private final Messages messages;

    public ZoneMenu(Plugin plugin, ZoneManager zones, Messages messages) { this.plugin = plugin; this.zones = zones; this.messages = messages; }

    public void openEntry(Player player, ZoneType type) {
        ZoneManager.ZoneConfig config = zones.config(type);
        Inventory inventory = Bukkit.createInventory(new Holder(Kind.ENTRY, type, false, 0), config.entrySize(), MessageFormatter.deserialize(SmallCaps.template(config.entryTitle())));
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, Component.empty(), List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler);
        ItemStack confirm = item(config.entryMaterial(), MessageFormatter.deserialize(SmallCaps.template(config.entryName())),
                config.entryLore().stream().map(line -> MessageFormatter.deserialize(SmallCaps.template(line))).toList());
        ItemMeta meta = confirm.getItemMeta();
        if (config.entryModel() != null) meta.setCustomModelData(config.entryModel());
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS); confirm.setItemMeta(meta);
        int center = inventory.getSize() / 2;
        inventory.setItem(center, confirm);
        inventory.setItem(center - 2, item(Material.COMPASS,
                messages.getGui(player, "zones.overview-status-name", "zone", type.displayName()),
                messages.getGuiList(player, "zones.overview-status-lore",
                        "players", String.valueOf(zones.playerCount(type)),
                        "mobs", String.valueOf(zones.mobCount(type)))));
        inventory.setItem(center + 2, item(Material.CHEST,
                messages.getGui(player, "zones.overview-loot-name"),
                messages.getGuiList(player, "zones.overview-loot-lore",
                        "items", String.valueOf(zones.loot(type).size()))));
        player.openInventory(inventory);
    }

    public void openProgress(Player player) {
        Inventory inventory = Bukkit.createInventory(new Holder(Kind.PROGRESS, null, false, 0), 27, messages.getGui(player, "zones.progress-title"));
        inventory.setItem(11, progressItem(player, ZoneType.HAVEN, Material.EMERALD));
        inventory.setItem(15, progressItem(player, ZoneType.RIFTLANDS, Material.CRYING_OBSIDIAN));
        inventory.setItem(13, eventItem(player));
        player.openInventory(inventory);
    }

    public void openLoot(Player player, ZoneType type, boolean admin) {
        openLoot(player, type, admin, 0);
    }

    private void openLoot(Player player, ZoneType type, boolean admin, int requestedPage) {
        List<ZoneManager.LootEntry> entries = zones.loot(type);
        int pages = lootPageCount(entries.size());
        int page = admin ? 0 : normalizeLootPage(requestedPage, entries.size());
        Inventory inventory = Bukkit.createInventory(new Holder(Kind.LOOT, type, admin, page), LOOT_MENU_SIZE,
                messages.getGui(player, admin ? "zones.loot-editor-title" : "zones.loot-title",
                        "zone", type.displayName(), "page", String.valueOf(page + 1),
                        "pages", String.valueOf(pages)));
        int start = page * EDITABLE_LOOT_SLOTS;
        for (int slot = 0; slot < EDITABLE_LOOT_SLOTS && start + slot < entries.size(); slot++) {
            ZoneManager.LootEntry entry = entries.get(start + slot);
            ItemStack display = admin ? zones.prepareLootEditorItem(type, entry) : entry.item();
            inventory.setItem(slot, displayLoot(player, type, display, entry.chance()));
        }
        if (admin) {
            inventory.setItem(LOOT_BACK_SLOT, item(Material.LIME_DYE,
                    messages.getGui(player, "zones.loot-editor-info-name"),
                    messages.getGuiList(player, "zones.loot-editor-info-lore")));
        } else {
            List<Component> informationLore = new ArrayList<>(messages.getGuiList(player, "zones.loot-info-lore"));
            informationLore.add(messages.getGui(player, "zones.loot-page-info", "page", String.valueOf(page + 1),
                    "pages", String.valueOf(pages), "items", String.valueOf(entries.size())));
            inventory.setItem(LOOT_INFO_SLOT, item(Material.BOOK,
                    messages.getGui(player, "zones.loot-info-name"), informationLore));
            inventory.setItem(LOOT_BACK_SLOT, item(Material.BARRIER,
                    messages.getGui(player, "zones.loot-back-name", "zone", type.displayName()), List.of()));
            if (page > 0) inventory.setItem(LOOT_PREVIOUS_SLOT, item(Material.ARROW,
                    messages.getGui(player, "zones.loot-previous-name"), List.of()));
            if (page + 1 < pages) inventory.setItem(LOOT_NEXT_SLOT, item(Material.ARROW,
                    messages.getGui(player, "zones.loot-next-name"), List.of()));
        }
        player.openInventory(inventory);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) { event.setCancelled(true); return; }
        if (holder.kind == Kind.ENTRY) {
            event.setCancelled(true);
            int center = event.getInventory().getSize() / 2;
            if (event.getRawSlot() == center - 2) {
                queueOpen(player, () -> openEntry(player, holder.type));
            } else if (event.getRawSlot() == center + 2) {
                queueOpen(player, () -> openLoot(player, holder.type, false));
            } else if (event.getRawSlot() == center) {
                if (!player.hasPermission("vertex.zones.use")) {
                    player.sendMessage(zones.message(player, "zones.no-permission"));
                    return;
                }
                player.closeInventory();
                String result = zones.beginEntry(player, holder.type);
                if (!"ok".equals(result)) {
                    String key = result.contains("cooldown") ? "zones.entry-cooldown"
                            : result.equals("combat") ? "zones.entry-combat"
                            : result.equals("loading") ? "zones.state-loading" : "zones.no-route";
                    String seconds = result.startsWith("cooldown:")
                            ? result.substring("cooldown:".length()) : "0";
                    player.sendMessage(zones.message(player, key, "seconds", seconds));
                }
            }
            return;
        }
        if (holder.kind == Kind.PROGRESS) { event.setCancelled(true); return; }
        if (holder.kind != Kind.LOOT) return;
        if (!holder.admin) {
            event.setCancelled(true);
            int rawSlot = event.getRawSlot();
            if (rawSlot == LOOT_BACK_SLOT) queueOpen(player, () -> openEntry(player, holder.type));
            else if (rawSlot == LOOT_PREVIOUS_SLOT && holder.page > 0)
                queueOpen(player, () -> openLoot(player, holder.type, false, holder.page - 1));
            else if (rawSlot == LOOT_NEXT_SLOT)
                queueOpen(player, () -> openLoot(player, holder.type, false, holder.page + 1));
            return;
        }
        int rawSlot = event.getRawSlot();
        if (rawSlot >= EDITABLE_LOOT_SLOTS && rawSlot < event.getInventory().getSize()) {
            event.setCancelled(true);
            return;
        }
        if (rawSlot >= 0 && rawSlot < EDITABLE_LOOT_SLOTS
                && event.getClickedInventory() == event.getInventory()
                && event.getCurrentItem() != null && !event.getCurrentItem().isEmpty()
                && event.isRightClick() && (event.getCursor() == null || event.getCursor().isEmpty())) {
            event.setCancelled(true);
            ItemStack clean = zones.finishLootEditorItem(holder.type, event.getCurrentItem());
            double adjusted = zones.lootChance(holder.type, clean) + (event.isShiftClick() ? -1D : 1D);
            zones.setLootChance(clean, adjusted);
            double chance = zones.lootChance(holder.type, clean);
            event.getInventory().setItem(rawSlot, displayLoot(player, holder.type,
                    zones.prepareLootEditorItem(holder.type, new ZoneManager.LootEntry(clean, chance)), chance));
        }
        queueEditorRefresh(player, event.getInventory(), holder);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder) || holder.kind != Kind.LOOT) return;
        if (!holder.admin) { event.setCancelled(true); return; }
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= EDITABLE_LOOT_SLOTS && rawSlot < event.getInventory().getSize()) {
                event.setCancelled(true);
                return;
            }
        }
        if (event.getWhoClicked() instanceof Player player) queueEditorRefresh(player, event.getInventory(), holder);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder) || holder.kind != Kind.LOOT || !holder.admin) return;
        List<ZoneManager.LootEntry> entries = new ArrayList<>();
        for (int slot = 0; slot < EDITABLE_LOOT_SLOTS; slot++) {
            ItemStack item = event.getInventory().getItem(slot);
            if (item == null || item.isEmpty()) continue;
            ItemStack clean = zones.finishLootEditorItem(holder.type, item);
            if (clean != null && !clean.isEmpty()) {
                entries.add(new ZoneManager.LootEntry(clean, zones.lootChance(holder.type, clean)));
            }
        }
        zones.saveLoot(holder.type, entries);
        if (event.getPlayer() instanceof Player player) player.sendMessage(zones.message(player, "zones.loot-saved"));
    }

    private ItemStack progressItem(Player player, ZoneType type, Material material) {
        long kills = zones.kills(player, type); long next = zones.nextMilestone(player, type);
        List<Component> lore = new ArrayList<>(); lore.add(messages.getGui(player,"zones.progress-kills","kills",String.valueOf(kills))); lore.add(messages.getGui(player,"zones.progress-amplification","amount",String.valueOf(zones.progressionBoost(player,type))));
        lore.add(messages.getGui(player,next==0?"zones.progress-maximum":"zones.progress-next","next",String.valueOf(next),"remaining",String.valueOf(Math.max(0,next-kills))));
        return item(material, messages.getGui(player,type==ZoneType.HAVEN?"zones.progress-haven":"zones.progress-riftlands","zone",type.displayName()), lore);
    }
    private ItemStack eventItem(Player player) {
        List<ZoneManager.Score> top = zones.topScoresForDisplay(); List<Component> lore = new ArrayList<>();
        lore.add(messages.getGui(player,zones.eventRemainingSeconds()>0?"zones.event-time":"zones.event-inactive","seconds",String.valueOf(zones.eventRemainingSeconds())));
        for (int i=0;i<top.size();i++) lore.add(messages.getGui(player,"zones.event-score","place",String.valueOf(i+1),"player",top.get(i).name(),"score",String.valueOf(top.get(i).score())));
        lore.add(messages.getGui(player,"zones.event-player-boost","amount",String.valueOf(zones.winnerBoost(player))));
        return item(Material.NETHER_STAR,messages.getGui(player,"zones.event-item-name"),lore);
    }
    private void queueEditorRefresh(Player player, Inventory inventory, Holder holder) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            cleanEditorItemsFromPlayer(player, holder.type);
            if (!player.isOnline() || player.getOpenInventory().getTopInventory() != inventory) return;
            for (int slot = 0; slot < EDITABLE_LOOT_SLOTS; slot++) {
                ItemStack item = inventory.getItem(slot);
                if (item == null || item.isEmpty() || zones.isLootEditorItem(item)) continue;
                ItemStack clean = zones.finishLootEditorItem(holder.type, item);
                inventory.setItem(slot, displayLoot(player, holder.type,
                        zones.prepareLootEditorItem(holder.type,
                                new ZoneManager.LootEntry(clean, zones.lootChance(holder.type, clean))),
                        zones.lootChance(holder.type, clean)));
            }
            player.updateInventory();
        });
    }

    private void cleanEditorItemsFromPlayer(Player player, ZoneType type) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (zones.isLootEditorItem(item)) player.getInventory().setItem(slot, zones.finishLootEditorItem(type, item));
        }
        ItemStack cursor = player.getItemOnCursor();
        if (zones.isLootEditorItem(cursor)) player.setItemOnCursor(zones.finishLootEditorItem(type, cursor));
    }

    private void queueOpen(Player player, Runnable action) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) action.run();
        });
    }

    private ItemStack displayLoot(Player player, ZoneType type, ItemStack source, double chance) {
        ItemStack copy = source.clone(); ItemMeta meta = copy.getItemMeta(); List<net.kyori.adventure.text.Component> lore = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
        lore.add(Component.empty()); lore.add(messages.getGui(player,"zones.loot-base-chance","chance",formatChance(chance)));
        lore.add(messages.getGui(player,"zones.loot-amplification","amount",String.valueOf(zones.amplification(player,type))));
        if (chance < zones.config(type).rareThreshold()) lore.add(messages.getGui(player,"zones.loot-rare-protection"));
        lore.add(messages.getGui(player,"zones.loot-extra-rolls")); meta.lore(lore); copy.setItemMeta(meta); return copy;
    }
    static int lootPageCount(int itemCount) {
        return Math.max(1, (Math.max(0, itemCount) + EDITABLE_LOOT_SLOTS - 1) / EDITABLE_LOOT_SLOTS);
    }
    static int normalizeLootPage(int requestedPage, int itemCount) {
        return Math.max(0, Math.min(requestedPage, lootPageCount(itemCount) - 1));
    }
    static String formatChance(double chance) {
        return java.math.BigDecimal.valueOf(chance).stripTrailingZeros().toPlainString();
    }
    private ItemStack item(Material material, Component name, List<Component> lore) { ItemStack item = new ItemStack(material); ItemMeta meta=item.getItemMeta();meta.displayName(name);meta.lore(lore);item.setItemMeta(meta);return item; }
    private enum Kind { ENTRY, PROGRESS, LOOT }
    private record Holder(Kind kind, ZoneType type, boolean admin, int page) implements InventoryHolder { @Override public Inventory getInventory() { return null; } }
}
