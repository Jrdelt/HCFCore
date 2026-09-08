package me.vertex.core.booster;

import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.lang.Messages;
import me.vertex.core.menu.MenuLayout;
import me.vertex.core.menu.MenuPlaceholders;
import me.vertex.core.menu.MenuRegistry;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /boosters}: one icon per category showing the bonus actually in
 * force, and a per-category breakdown behind a click.
 *
 * <p>Every figure comes from {@link BoosterService}, which reads the systems
 * that own the bonuses, so this cannot show a number the server would not
 * apply. Inactive sources are listed with their reason rather than hidden --
 * "why am I not getting this" is the question the screen exists to answer.
 *
 * <p>All sizing, slots, materials, names, lore, and sounds live in
 * {@code gui/boosters.yml}; this class decides only what data goes in.
 */
public final class BoostersMenu {

    public static final String MENU_ID = "boosters";

    private static final int[] DEFAULT_CATEGORY_SLOTS = {11, 12, 13, 14, 15};
    private static final int[] DEFAULT_SOURCE_SLOTS = {10, 11, 12, 13, 14, 15, 16};

    private BoostersMenu() {
    }

    public static void openOverview(Player viewer, Player subject, BoosterService service, Messages messages,
            MenuRegistry menus) {
        MenuLayout layout = menus.layout(MENU_ID);
        Holder holder = new Holder(subject.getUniqueId(), subject.getName(), null);
        Inventory inventory = layout.createInventory(holder,
                viewer == subject ? null : "inspect",
                MenuPlaceholders.of().put("player", subject.getName()));
        holder.inventory = inventory;

        int[] slots = layout.slots("category-slots", DEFAULT_CATEGORY_SLOTS);
        BoosterCategory[] categories = BoosterCategory.values();
        for (int index = 0; index < categories.length && index < slots.length; index++) {
            BoosterCategory category = categories[index];
            BoosterStacking.Result result = service.result(subject, category);
            MenuPlaceholders placeholders = MenuPlaceholders.of()
                    .put("category", messages.getRaw(viewer, "boosters.category-" + category.configKey()))
                    .put("effective", percent(result.effective()))
                    .put("raw", percent(result.raw()))
                    .put("cap", percent(service.rules(category).cap()))
                    .putBlock("sources", sourceLines(viewer, service, subject, messages, category, layout));

            // A capped category gets its own template so the raw total and the
            // cap can be shown; otherwise that would be the same number twice.
            String templateId = result.capped() ? "category-capped" : "category";
            var template = layout.item(templateId);
            if (template == null) {
                continue;
            }
            inventory.setItem(slots[index], template.render(iconMaterial(category), placeholders));
            holder.slotCategories.put(slots[index], category);
        }
        viewer.openInventory(inventory);
    }

    public static void openDetail(Player viewer, Player subject, BoosterService service, Messages messages,
            MenuRegistry menus, BoosterCategory category) {
        MenuLayout layout = menus.layout(MENU_ID);
        Holder holder = new Holder(subject.getUniqueId(), subject.getName(), category);
        Inventory inventory = layout.createInventory(holder, "detail", MenuPlaceholders.of()
                .put("player", subject.getName())
                .put("category", messages.getRaw(viewer, "boosters.category-" + category.configKey())));
        holder.inventory = inventory;

        List<BoosterContribution> contributions = service.contributions(subject, category);
        int[] slots = layout.slots("source-slots", DEFAULT_SOURCE_SLOTS);
        for (int index = 0; index < contributions.size() && index < slots.length; index++) {
            BoosterContribution contribution = contributions.get(index);
            var template = layout.item(contribution.active() ? "source-active" : "source-inactive");
            if (template == null) {
                continue;
            }
            inventory.setItem(slots[index], template.render(placeholdersFor(viewer, messages, contribution)));
        }
        if (contributions.isEmpty()) {
            layout.place(inventory, "empty", MenuPlaceholders.of());
        }
        layout.place(inventory, "back", MenuPlaceholders.of());
        viewer.openInventory(inventory);
    }

    /** The Back button's configured slot, so the listener never hardcodes it. */
    public static int backSlot(MenuRegistry menus) {
        var back = menus.layout(MENU_ID).item("back");
        return back == null ? -1 : back.slot();
    }

    private static MenuPlaceholders placeholdersFor(Player viewer, Messages messages,
            BoosterContribution contribution) {
        MenuPlaceholders placeholders = MenuPlaceholders.of()
                .put("source", messages.getRaw(viewer, "boosters.source-" + contribution.sourceId()))
                .put("percent", percent(contribution.percent()));
        if (!contribution.active()) {
            placeholders.put("reason",
                    messages.getRaw(viewer, "boosters.reason-" + contribution.inactiveReasonKey()));
        }
        return placeholders;
    }

    private static List<Component> sourceLines(Player viewer, BoosterService service, Player subject,
            Messages messages, BoosterCategory category, MenuLayout layout) {
        List<Component> lines = new ArrayList<>();
        for (BoosterContribution contribution : service.contributions(subject, category)) {
            lines.add(MessageFormatter.deserialize(messages.getRaw(viewer,
                    contribution.active() ? "boosters.line-active" : "boosters.line-inactive",
                    "source", messages.getRaw(viewer, "boosters.source-" + contribution.sourceId()),
                    "percent", percent(contribution.percent()))));
        }
        if (lines.isEmpty()) {
            var empty = layout.item("no-sources");
            lines.add(empty != null
                    ? MessageFormatter.deserialize(messages.getRaw(viewer, "boosters.no-sources"))
                    : Component.empty());
        }
        return lines;
    }

    private static Material iconMaterial(BoosterCategory category) {
        return switch (category) {
            case ORE_DROP -> Material.DIAMOND_PICKAXE;
            case SELL -> Material.EMERALD;
            case MOB_SPAWN_RATE -> Material.SPAWNER;
            case MOB_DROP -> Material.ROTTEN_FLESH;
            case EXP -> Material.EXPERIENCE_BOTTLE;
        };
    }

    /** Trailing zeros dropped, so a whole bonus reads "+15%" rather than "+15.0%". */
    private static String percent(double value) {
        return value == Math.rint(value)
                ? String.valueOf((long) value)
                : String.valueOf(Math.round(value * 100D) / 100D);
    }

    public static final class Holder implements InventoryHolder {
        private final UUID subjectUuid;
        private final String subjectName;
        private final BoosterCategory category;
        private final Map<Integer, BoosterCategory> slotCategories = new HashMap<>();
        private Inventory inventory;

        Holder(UUID subjectUuid, String subjectName, BoosterCategory category) {
            this.subjectUuid = subjectUuid;
            this.subjectName = subjectName;
            this.category = category;
        }

        public UUID subjectUuid() {
            return subjectUuid;
        }

        public String subjectName() {
            return subjectName;
        }

        /** Null on the overview, set on a per-category detail view. */
        public BoosterCategory category() {
            return category;
        }

        public BoosterCategory categoryAt(int slot) {
            return slotCategories.get(slot);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
