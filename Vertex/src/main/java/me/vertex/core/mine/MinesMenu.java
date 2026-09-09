package me.vertex.core.mine;

import me.vertex.core.booster.BoosterCategory;
import me.vertex.core.booster.BoosterContribution;
import me.vertex.core.booster.BoosterService;
import me.vertex.core.booster.BoosterStacking;
import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.lang.Messages;
import me.vertex.core.menu.MenuLayout;
import me.vertex.core.menu.MenuPlaceholders;
import me.vertex.core.menu.MenuRegistry;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /mines}: the two mining worlds and enough live status to decide
 * where to go, with the detail behind a click.
 *
 * <p>The mining-bonus breakdown comes from the same {@link BoosterService}
 * that {@code /boosters} reads, so the two screens cannot disagree about what
 * a player is actually getting.
 */
public final class MinesMenu {

    public static final String MENU_ID = "mines";
    private static final int[] DEFAULT_MINE_SLOTS = {12, 14};

    private MinesMenu() {
    }

    public static void openOverview(Player viewer, MineManager mines, MineKothManager koths,
            HotZoneManager hotZones, BoosterService boosters, Messages messages, MenuRegistry menus) {
        MenuLayout layout = menus.layout(MENU_ID);
        Holder holder = new Holder(null);
        Inventory inventory = layout.createInventory(holder, MenuPlaceholders.of());
        holder.inventory = inventory;

        int[] slots = layout.slots("mine-slots", DEFAULT_MINE_SLOTS);
        List<MineRegion> regions = mines.regions();
        for (int index = 0; index < regions.size() && index < slots.length; index++) {
            MineRegion region = regions.get(index);
            var template = layout.item(region.isDefined() ? "mine" : "mine-unplaced");
            if (template == null) {
                continue;
            }
            inventory.setItem(slots[index],
                    template.render(region.icon(), overviewPlaceholders(viewer, region, mines, koths, hotZones, messages)));
            holder.slotMines.put(slots[index], region.id());
        }
        viewer.openInventory(inventory);
    }

    public static void openDetail(Player viewer, String mineId, MineManager mines, MineKothManager koths,
            HotZoneManager hotZones, BoosterService boosters, Messages messages, MenuRegistry menus) {
        MineRegion region = mines.region(mineId);
        if (region == null) {
            return;
        }
        MenuLayout layout = menus.layout(MENU_ID);
        Holder holder = new Holder(mineId);
        Inventory inventory = layout.createInventory(holder, "detail",
                MenuPlaceholders.of().put("mine", region.displayName()));
        holder.inventory = inventory;

        layout.place(inventory, "world-info", worldPlaceholders(viewer, region, messages));
        layout.place(inventory, "generation", MenuPlaceholders.of()
                .putBlock("generation", generationLines(region, hotZones)));
        layout.place(inventory, "koth", kothPlaceholders(region, koths, mines, messages));
        layout.place(inventory, "hotzone", hotZonePlaceholders(region, hotZones, messages));
        layout.place(inventory, "your-bonus", bonusPlaceholders(viewer, boosters, messages));
        layout.place(inventory, "back", MenuPlaceholders.of());
        viewer.openInventory(inventory);
    }

    private static MenuPlaceholders overviewPlaceholders(Player viewer, MineRegion region, MineManager mines,
            MineKothManager koths, HotZoneManager hotZones, Messages messages) {
        MineKothDefinition koth = mines.kothDefinition(region.id());
        Integer owner = koths.ownerOf(region.id());
        long held = koths.heldSeconds(region.id());
        int factionId = FactionsHook.getFactionId(viewer);

        MenuPlaceholders placeholders = MenuPlaceholders.of()
                .put("mine", region.displayName())
                .put("world", region.world())
                .putTrusted("pvp", pvpLabel(region, messages, viewer))
                .put("ores", oreList(region));
        putOwnerName(placeholders, "koth_owner", owner, messages, viewer);
        return placeholders
                .put("koth_held", owner == null ? "-" : duration(held))
                .put("koth_booster", kothBonus(koth, koths, region))
                .put("koth_next", nextTier(koth, held))
                .putTrusted("hotzone", hotZoneLabel(region, hotZones, messages, viewer))
                .putTrusted("eligible", owner != null && factionId != FactionsHook.NO_FACTION && owner == factionId
                        ? messages.getRaw(viewer, "mines.eligible-yes")
                        : messages.getRaw(viewer, "mines.eligible-no"));
    }

    private static MenuPlaceholders worldPlaceholders(Player viewer, MineRegion region, Messages messages) {
        long here = Bukkit.getOnlinePlayers().stream()
                .filter(player -> player.getWorld().getName().equalsIgnoreCase(region.world()))
                .count();
        return MenuPlaceholders.of()
                .put("world", region.world())
                .putTrusted("pvp", pvpLabel(region, messages, viewer))
                .put("players", String.valueOf(here))
                .putTrusted("inside", viewer.getWorld().getName().equalsIgnoreCase(region.world())
                        ? messages.getRaw(viewer, "mines.eligible-yes")
                        : messages.getRaw(viewer, "mines.eligible-no"))
                .put("regen", String.valueOf(region.regenDelaySeconds()));
    }

    private static MenuPlaceholders kothPlaceholders(MineRegion region, MineKothManager koths,
            MineManager mines, Messages messages) {
        MineKothDefinition koth = mines.kothDefinition(region.id());
        Integer owner = koths.ownerOf(region.id());
        long held = koths.heldSeconds(region.id());
        MenuPlaceholders placeholders = MenuPlaceholders.of()
                .put("koth_control", String.valueOf(Math.round(koths.controlOf(region.id()))))
                .put("koth_held", owner == null ? "-" : duration(held))
                .put("koth_booster", kothBonus(koth, koths, region))
                .put("koth_next", nextTier(koth, held));
        putOwnerName(placeholders, "koth_owner", owner, messages, null);
        return placeholders;
    }

    /**
     * A KOTH's owner is either the trusted, admin-colored "unclaimed" lang
     * snippet or a player-chosen faction name -- only the former is safe to
     * insert without escaping its formatting.
     */
    private static void putOwnerName(MenuPlaceholders placeholders, String key, Integer factionId,
            Messages messages, Player viewer) {
        if (factionId == null) {
            placeholders.putTrusted(key, messages.getRaw(viewer, "mines.koth-unclaimed"));
            return;
        }
        String name = FactionsHook.getFactionName(factionId);
        placeholders.put(key, name == null ? String.valueOf(factionId) : name);
    }

    private static MenuPlaceholders hotZonePlaceholders(MineRegion region, HotZoneManager hotZones,
            Messages messages) {
        boolean active = hotZones.isActive(region.id());
        return MenuPlaceholders.of()
                .putTrusted("hotzone", messages.getRaw(null, active ? "mines.hotzone-active" : "mines.hotzone-inactive"))
                .put("hotzone_percent", trimmed(hotZones.oreDropPercent()))
                .put("hotzone_remaining", active ? duration(hotZones.remainingSeconds()) : "-")
                .put("hotzone_next", active ? "-" : duration(hotZones.secondsUntilNext()));
    }

    private static MenuPlaceholders bonusPlaceholders(Player viewer, BoosterService boosters, Messages messages) {
        List<Component> lines = new ArrayList<>();
        for (BoosterContribution contribution : boosters.contributions(viewer, BoosterCategory.ORE_DROP)) {
            lines.add(MessageFormatter.deserialize(messages.getRaw(viewer,
                    contribution.active() ? "boosters.line-active" : "boosters.line-inactive",
                    "source", MessageFormatter.plain(messages.getRaw(viewer, "boosters.source-" + contribution.sourceId())),
                    "percent", trimmed(contribution.percent()))));
        }
        if (lines.isEmpty()) {
            lines.add(MessageFormatter.deserialize(messages.getRaw(viewer, "boosters.no-sources")));
        }
        BoosterStacking.Result result = boosters.result(viewer, BoosterCategory.ORE_DROP);
        return MenuPlaceholders.of()
                .putBlock("breakdown", lines)
                .put("effective", trimmed(result.effective()));
    }

    /** One line per configured block, read from the mine rather than written into the GUI file. */
    private static List<Component> generationLines(MineRegion region, HotZoneManager hotZones) {
        MineOreTable table = hotZones.tableFor(region);
        List<Component> lines = new ArrayList<>();
        table.asPercentages().forEach((material, percent) -> lines.add(MessageFormatter.deserialize(
                "<gray>" + material.name().toLowerCase(Locale.ROOT).replace('_', ' ')
                        + ": <white>" + trimmed(Math.round(percent * 1000D) / 1000D) + "%")));
        return lines;
    }

    private static String kothBonus(MineKothDefinition koth, MineKothManager koths, MineRegion region) {
        if (koth == null || koths.ownerOf(region.id()) == null) {
            return "+0%";
        }
        return "+" + trimmed(koth.booster().percentFor(koths.heldSeconds(region.id()))) + "%";
    }

    private static String nextTier(MineKothDefinition koth, long heldSeconds) {
        if (koth == null) {
            return "-";
        }
        MineKothBooster.Tier next = koth.booster().nextTier(heldSeconds);
        return next == null ? "-" : "+" + trimmed(next.percent()) + "% in " + duration(
                koth.booster().secondsUntilNextTier(heldSeconds));
    }

    private static String pvpLabel(MineRegion region, Messages messages, Player viewer) {
        return messages.getRaw(viewer, region.pvp() == MineRegion.PvpMode.ENABLED
                ? "mines.pvp-everywhere" : "mines.pvp-koth-only");
    }

    private static String hotZoneLabel(MineRegion region, HotZoneManager hotZones, Messages messages, Player viewer) {
        return hotZones.isActive(region.id())
                ? messages.getRaw(viewer, "mines.hotzone-active-short",
                        "percent", trimmed(hotZones.oreDropPercent()))
                : messages.getRaw(viewer, "mines.hotzone-inactive");
    }

    private static String oreList(MineRegion region) {
        return String.join(", ", region.ores().materials().stream()
                .filter(region::isOre)
                .map(material -> material.name().toLowerCase(Locale.ROOT).replace("_ore", "").replace('_', ' '))
                .toList());
    }

    private static String duration(long seconds) {
        if (seconds <= 0) {
            return "-";
        }
        long hours = seconds / 3600;
        long minutes = seconds % 3600 / 60;
        long secs = seconds % 60;
        return hours > 0 ? hours + "h " + minutes + "m" : minutes > 0 ? minutes + "m " + secs + "s" : secs + "s";
    }

    private static String trimmed(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    public static final class Holder implements InventoryHolder {
        private final String mineId;
        private final Map<Integer, String> slotMines = new HashMap<>();
        private Inventory inventory;

        Holder(String mineId) {
            this.mineId = mineId;
        }

        /** Null on the overview, set on a mine's detail page. */
        public String mineId() {
            return mineId;
        }

        public String mineAt(int slot) {
            return slotMines.get(slot);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
