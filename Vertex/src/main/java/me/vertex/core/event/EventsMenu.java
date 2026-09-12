package me.vertex.core.event;

import me.vertex.core.factions.FactionsHook;
import me.vertex.core.capture.CaptureEventManager;
import me.vertex.core.capture.CaptureEventType;
import me.vertex.core.lang.Messages;
import me.vertex.core.menu.MenuItemTemplate;
import me.vertex.core.menu.MenuLayout;
import me.vertex.core.menu.MenuPlaceholders;
import me.vertex.core.menu.MenuRegistry;
import me.vertex.core.mine.HotZoneManager;
import me.vertex.core.mine.MineKothDefinition;
import me.vertex.core.mine.MineKothManager;
import me.vertex.core.mine.MineManager;
import me.vertex.core.mine.MineRegion;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;

/**
 * Read-only central event board. It consumes the authoritative Mine KOTH and
 * Hot Zone managers rather than maintaining another cache of event state.
 */
public final class EventsMenu {

    public static final String MENU_ID = "events";
    private static final int[] DEFAULT_MINE_SLOTS = {14, 15, 16, 19, 20, 21, 22, 23, 24, 25};

    private EventsMenu() {
    }

    public static void open(Player viewer, MineManager mines, MineKothManager koths,
            HotZoneManager hotZones, CaptureEventManager captureEvents, Messages messages, MenuRegistry menus) {
        MenuLayout layout = menus.layout(MENU_ID);
        Holder holder = new Holder();
        Inventory inventory = layout.createInventory(holder, MenuPlaceholders.of());
        holder.inventory = inventory;
        render(inventory, viewer, mines, koths, hotZones, captureEvents, messages, menus);
        viewer.openInventory(inventory);
    }

    /** Re-renders an open hub at its configured interval without reopening it. */
    public static void refreshOpen(Player viewer, MineManager mines, MineKothManager koths,
            HotZoneManager hotZones, CaptureEventManager captureEvents, Messages messages, MenuRegistry menus,
            long currentTick) {
        if (!(viewer.getOpenInventory().getTopInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        long interval = menus.layout(MENU_ID).refreshTicks();
        if (interval <= 0L || currentTick - holder.lastRenderTick < interval) {
            return;
        }
        render(holder.inventory, viewer, mines, koths, hotZones, captureEvents, messages, menus);
        holder.lastRenderTick = currentTick;
    }

    private static void render(Inventory inventory, Player viewer, MineManager mines, MineKothManager koths,
            HotZoneManager hotZones, CaptureEventManager captureEvents, Messages messages, MenuRegistry menus) {
        MenuLayout layout = menus.layout(MENU_ID);
        // Build an empty layout first so configured filler panes are restored
        // on every live refresh, then copy its contents into the open view.
        Inventory base = layout.createInventory(null, MenuPlaceholders.of());
        inventory.setContents(base.getContents());
        Holder holder = inventory.getHolder() instanceof Holder value ? value : null;
        if (holder != null) holder.actions.clear();

        MenuItemTemplate artifact = layout.item("artifact-unavailable");
        if (artifact != null) {
            for (int slot : artifact.slots()) {
                if (slot >= 0 && slot < inventory.getSize()) {
                    inventory.setItem(slot, artifact.render(MenuPlaceholders.of()));
                }
            }
        }

        MenuItemTemplate haven = layout.item("haven");
        if (haven != null && haven.slot() >= 0 && haven.slot() < inventory.getSize()) {
            inventory.setItem(haven.slot(), haven.render(MenuPlaceholders.of()
                    .put("players", String.valueOf(0)).put("mobs", String.valueOf(0))));
            if (holder != null) holder.actions.put(haven.slot(), ClickAction.HAVEN);
        }
        MenuItemTemplate riftlands = layout.item("riftlands");
        if (riftlands != null && riftlands.slot() >= 0 && riftlands.slot() < inventory.getSize()) {
            inventory.setItem(riftlands.slot(), riftlands.render(MenuPlaceholders.of()
                    .put("players", String.valueOf(0)).put("mobs", String.valueOf(0))));
            if (holder != null) holder.actions.put(riftlands.slot(), ClickAction.RIFTLANDS);
        }

        MenuItemTemplate mineTemplate = layout.item("mine");
        int[] slots = layout.slots("mine-slots", DEFAULT_MINE_SLOTS);
        int index = 0;
        if (mineTemplate != null) {
            for (MineRegion mine : mines.regions()) {
                if (!mine.isDefined() || index >= slots.length) continue;
                MineKothDefinition definition = mines.kothDefinitions().stream()
                        .filter(candidate -> candidate.mineId().equalsIgnoreCase(mine.id()) && candidate.isDefined())
                        .findFirst().orElse(null);
                Integer ownerId = koths.ownerOf(mine.id());
                String owner = ownerId == null ? messages.getRaw(viewer, "events.unclaimed") : factionName(ownerId);
                long held = koths.heldSeconds(mine.id());
                double bonus = definition == null || ownerId == null ? 0D : definition.booster().percentFor(held);
                MenuPlaceholders placeholders = MenuPlaceholders.of()
                        .put("mine", mine.displayName()).put("world", mine.world()).put("owner", owner)
                        .put("control", String.valueOf(Math.round(koths.controlOf(mine.id()))))
                        .put("held", duration(held)).put("bonus", "+" + trimmed(bonus) + "%");
                int slot = slots[index++];
                if (slot >= 0 && slot < inventory.getSize()) inventory.setItem(slot, mineTemplate.render(mine.icon(), placeholders));
            }
        }

        MineRegion hotMine = mines.regions().stream().filter(region -> hotZones.isActive(region.id()))
                .findFirst().orElse(null);
        layout.place(inventory, "hot-zone", MenuPlaceholders.of()
                .putTrusted("state", messages.getRaw(viewer,
                        hotMine == null ? "events.hotzone-scheduled" : "events.hotzone-active"))
                .put("mine", hotMine == null ? messages.getRaw(viewer, "events.none") : hotMine.displayName())
                .put("world", hotMine == null ? messages.getRaw(viewer, "events.none") : hotMine.world())
                .put("bonus", "+" + trimmed(hotZones.oreDropPercent()) + "%")
                .put("remaining", hotMine == null ? messages.getRaw(viewer, "events.none")
                        : duration(hotZones.remainingSeconds()))
                .put("next", hotMine == null ? duration(hotZones.secondsUntilNext())
                        : messages.getRaw(viewer, "events.none")));

        CaptureEventManager.EventSnapshot koth = captureEvents.snapshot(CaptureEventType.KOTH);
        java.util.List<net.kyori.adventure.text.Component> details = new java.util.ArrayList<>();
        if (koth.active()) {
            details.add(messages.getGui(viewer, "events.koth-status", "name", koth.name()));
            details.add(messages.getGui(viewer, "events.koth-owner", "owner", koth.owner()));
            details.add(messages.getGui(viewer, "events.koth-remaining",
                    "time", duration(koth.remainingSeconds())));
        }
        if (koth.nextScheduledSeconds() >= 0L) {
            details.add(messages.getGui(viewer, "events.koth-next",
                    "time", duration(koth.nextScheduledSeconds())));
        }
        // No active/loaded KOTH deliberately produces no status line. The
        // configured icon is still placed and remains visible.
        layout.place(inventory, "koth", MenuPlaceholders.of().putBlock("details", details));
    }

    private static String factionName(int factionId) {
        String name = FactionsHook.getFactionName(factionId);
        return name == null ? String.valueOf(factionId) : name;
    }

    private static String duration(long seconds) {
        long safe = Math.max(0L, seconds);
        long hours = safe / 3_600L;
        long minutes = safe % 3_600L / 60L;
        long remainder = safe % 60L;
        return hours > 0L ? hours + "h " + minutes + "m" : minutes > 0L ? minutes + "m " + remainder + "s"
                : remainder + "s";
    }

    private static String trimmed(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    public static final class Holder implements InventoryHolder {
        private Inventory inventory;
        private long lastRenderTick;
        private final Map<Integer, ClickAction> actions = new HashMap<>();

        ClickAction action(int slot) { return actions.get(slot); }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    enum ClickAction { HAVEN, RIFTLANDS }
}
