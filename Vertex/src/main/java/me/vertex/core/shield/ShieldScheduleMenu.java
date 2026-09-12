package me.vertex.core.shield;

import me.vertex.core.factions.FactionData;
import me.vertex.core.factions.FactionMember;
import me.vertex.core.factions.FactionRole;
import me.vertex.core.factions.FactionsHook;
import me.vertex.core.grace.DurationParser;
import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.time.DayOfWeek;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Stages one complete weekly Shield schedule and commits it with one save action. */
public final class ShieldScheduleMenu implements Listener {
    private static final List<Integer> DAY_SLOTS = List.of(10, 11, 12, 13, 14, 15, 16);
    private static final int PVP_SLOT = 31;
    private static final int SAVE_SLOT = 40;
    private static final int CLOSE_SLOT = 44;

    private final ShieldManager manager;
    private final Messages messages;
    private final NamespacedKey actionKey;

    public ShieldScheduleMenu(Plugin plugin, ShieldManager manager, Messages messages) {
        this.manager = manager;
        this.messages = messages;
        this.actionKey = new NamespacedKey(plugin, "shield_menu_action");
    }

    public void open(Player player) {
        FactionData faction = FactionsHook.getFaction(player).orElse(null);
        if (faction == null) {
            player.sendMessage(messages.get(player, "shield.no-faction"));
            return;
        }
        FactionMember member = FactionsHook.service().member(player.getUniqueId());
        boolean editor = member != null
                && (member.role() == FactionRole.LEADER || member.role() == FactionRole.COLEADER);
        Holder holder = new Holder(faction.id(), editor, manager.editableSchedule(faction.id()));
        Inventory inventory = Bukkit.createInventory(holder, 54, messages.getGui(player, "shield.gui.title"));
        holder.inventory = inventory;
        render(player, holder);
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
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        String action = clicked.getItemMeta().getPersistentDataContainer()
                .get(actionKey, PersistentDataType.STRING);
        if (action == null) return;
        if (action.equals("close")) {
            player.closeInventory();
            return;
        }
        if (!holder.editor) {
            player.sendMessage(messages.get(player, "shield.configure-denied"));
            return;
        }
        if (action.equals("save")) {
            save(player, holder);
            return;
        }
        if (action.equals("pvp")) {
            holder.draft = holder.draft.withPvpProtected(!holder.draft.pvpProtected());
            render(player, holder);
            return;
        }
        if (!action.startsWith("day:")) return;
        int day = Integer.parseInt(action.substring(4));
        ShieldSchedule.Window old = holder.draft.day(day);
        int start = old.startMinute();
        int duration = old.durationMinutes();
        ClickType click = event.getClick();
        if (click == ClickType.LEFT) duration = Math.min(manager.maximumDailyMinutes(), duration + 60);
        else if (click == ClickType.SHIFT_LEFT) duration = Math.max(0, duration - 60);
        else if (click == ClickType.RIGHT) start = Math.floorMod(start + 60, 1_440);
        else if (click == ClickType.SHIFT_RIGHT) start = Math.floorMod(start - 60, 1_440);
        else return;
        holder.draft = holder.draft.withDay(day, new ShieldSchedule.Window(start, duration));
        render(player, holder);
    }

    private void save(Player player, Holder holder) {
        FactionMember current = FactionsHook.service().member(player.getUniqueId());
        if (current == null || current.factionId() != holder.factionId) {
            player.closeInventory();
            player.sendMessage(messages.get(player, "shield.no-faction"));
            return;
        }
        if (current.role() != FactionRole.LEADER && current.role() != FactionRole.COLEADER) {
            player.closeInventory();
            player.sendMessage(messages.get(player, "shield.configure-denied"));
            return;
        }
        ShieldManager.ScheduleResult result = manager.replaceSchedule(holder.factionId,
                holder.draft, player.getUniqueId(), false);
        switch (result) {
            case OK -> {
                player.closeInventory();
                player.sendMessage(messages.get(player, "shield.schedule-saved", "time",
                        DurationParser.format(Math.max(1L,
                                (manager.pendingActivatesInMillis(holder.factionId) + 999L) / 1_000L))));
            }
            case LOCKED -> player.sendMessage(messages.get(player, "shield.schedule-locked", "time",
                    DurationParser.format(Math.max(1L,
                            (manager.scheduleLockedForMillis(holder.factionId) + 999L) / 1_000L))));
            case INVALID -> player.sendMessage(messages.get(player, "shield.schedule-invalid"));
            case NO_PERMISSION -> {
                player.closeInventory();
                player.sendMessage(messages.get(player, "shield.configure-denied"));
            }
            case STORAGE_ERROR -> player.sendMessage(messages.get(player, "shield.persist-failed"));
        }
    }

    private void render(Player player, Holder holder) {
        Inventory inventory = holder.inventory;
        ItemStack border = item(player, Material.GRAY_STAINED_GLASS_PANE, "shield.gui.border", List.of(), null);
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, border);
        for (int day = 0; day < 7; day++) {
            ShieldSchedule.Window window = holder.draft.day(day);
            String dayName = DayOfWeek.of(day + 1).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            List<net.kyori.adventure.text.Component> lore = messages.getGuiList(player, "shield.gui.day-lore",
                    "start", clock(window.startMinute()),
                    "duration", window.durationMinutes() == 0 ? messages.getRaw(player, "shield.gui.disabled")
                            : DurationParser.format(window.durationMinutes() * 60L),
                    "max", DurationParser.format(manager.maximumDailyMinutes() * 60L));
            inventory.setItem(DAY_SLOTS.get(day), item(player,
                    window.durationMinutes() == 0 ? Material.GRAY_DYE : Material.CLOCK,
                    "shield.gui.day-name", lore, "day:" + day, "day", dayName));
        }
        inventory.setItem(PVP_SLOT, item(player,
                holder.draft.pvpProtected() ? Material.LIME_DYE : Material.RED_DYE,
                holder.draft.pvpProtected() ? "shield.gui.pvp-on" : "shield.gui.pvp-off",
                messages.getGuiList(player, "shield.gui.pvp-lore"), "pvp"));
        inventory.setItem(SAVE_SLOT, item(player, holder.editor ? Material.EMERALD : Material.BARRIER,
                holder.editor ? "shield.gui.save" : "shield.gui.view-only",
                messages.getGuiList(player, holder.editor ? "shield.gui.save-lore" : "shield.gui.view-only-lore"),
                holder.editor ? "save" : null));
        inventory.setItem(CLOSE_SLOT, item(player, Material.BARRIER, "shield.gui.close", List.of(), "close"));
    }

    private ItemStack item(Player player, Material material, String namePath,
            List<net.kyori.adventure.text.Component> lore, String action, String... placeholders) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.getGui(player, namePath, placeholders));
        if (!lore.isEmpty()) meta.lore(new ArrayList<>(lore));
        if (action != null) meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        return item;
    }

    private static String clock(int minute) {
        int hour = minute / 60;
        int displayHour = hour % 12;
        if (displayHour == 0) displayHour = 12;
        return String.format(Locale.ROOT, "%d:%02d %s", displayHour, minute % 60, hour < 12 ? "AM" : "PM");
    }

    private static final class Holder implements InventoryHolder {
        private final int factionId;
        private final boolean editor;
        private ShieldSchedule draft;
        private Inventory inventory;

        private Holder(int factionId, boolean editor, ShieldSchedule draft) {
            this.factionId = factionId;
            this.editor = editor;
            this.draft = draft;
        }

        @Override public Inventory getInventory() { return inventory; }
    }
}
