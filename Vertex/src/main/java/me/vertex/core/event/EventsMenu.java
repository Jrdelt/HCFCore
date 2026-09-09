package me.vertex.core.event;

import me.vertex.core.factions.FactionsHook;
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

/**
 * Read-only central event board. It consumes the authoritative Mine KOTH and
 * Hot Zone managers rather than maintaining another cache of event state.
 */
public final class EventsMenu {

    public static final String MENU_ID = "events";
    private static final int[] DEFAULT_KOTH_SLOTS = {10, 12, 14, 16};

    private EventsMenu() {
    }

    public static void open(Player viewer, MineManager mines, MineKothManager koths,
            HotZoneManager hotZones, Messages messages, MenuRegistry menus) {
        MenuLayout layout = menus.layout(MENU_ID);
        Holder holder = new Holder();
        Inventory inventory = layout.createInventory(holder, MenuPlaceholders.of());
        holder.inventory = inventory;
        render(inventory, viewer, mines, koths, hotZones, messages, menus);
        viewer.openInventory(inventory);
    }

    /** Re-renders an open hub at its configured interval without reopening it. */
    public static void refreshOpen(Player viewer, MineManager mines, MineKothManager koths,
            HotZoneManager hotZones, Messages messages, MenuRegistry menus, long currentTick) {
        if (!(viewer.getOpenInventory().getTopInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        long interval = menus.layout(MENU_ID).refreshTicks();
        if (interval <= 0L || currentTick - holder.lastRenderTick < interval) {
            return;
        }
        render(holder.inventory, viewer, mines, koths, hotZones, messages, menus);
        holder.lastRenderTick = currentTick;
    }

    private static void render(Inventory inventory, Player viewer, MineManager mines, MineKothManager koths,
            HotZoneManager hotZones, Messages messages, MenuRegistry menus) {
        MenuLayout layout = menus.layout(MENU_ID);
        // Build an empty layout first so configured filler panes are restored
        // on every live refresh, then copy its contents into the open view.
        Inventory base = layout.createInventory(null, MenuPlaceholders.of());
        inventory.setContents(base.getContents());

        MenuItemTemplate artifact = layout.item("artifact-unavailable");
        if (artifact != null) {
            for (int slot : artifact.slots()) {
                if (slot >= 0 && slot < inventory.getSize()) {
                    inventory.setItem(slot, artifact.render(MenuPlaceholders.of()));
                }
            }
        }

        MenuItemTemplate kothTemplate = layout.item("mine-koth");
        int[] slots = layout.slots("mine-koth-slots", DEFAULT_KOTH_SLOTS);
        int index = 0;
        if (kothTemplate != null) {
            for (MineKothDefinition definition : mines.kothDefinitions()) {
                if (!definition.isDefined() || index >= slots.length) {
                    continue;
                }
                MineRegion mine = mines.region(definition.mineId());
                if (mine == null) {
                    continue;
                }
                Integer ownerId = koths.ownerOf(definition.mineId());
                String owner = ownerId == null ? messages.getRaw(viewer, "events.unclaimed") : factionName(ownerId);
                long held = koths.heldSeconds(definition.mineId());
                MenuPlaceholders placeholders = MenuPlaceholders.of()
                        .put("mine", mine.displayName())
                        .put("world", mine.world())
                        .put("owner", owner)
                        .put("control", String.valueOf(Math.round(koths.controlOf(definition.mineId()))))
                        .put("held", duration(held))
                        .put("bonus", "+" + trimmed(definition.booster().percentFor(held)) + "%");
                int slot = slots[index++];
                if (slot >= 0 && slot < inventory.getSize()) {
                    inventory.setItem(slot, kothTemplate.render(mine.icon(), placeholders));
                }
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

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
