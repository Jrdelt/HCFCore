package me.vertex.core.booster;

import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /boosters}: one icon per category showing the bonus actually in
 * force, and a per-category breakdown behind a click.
 *
 * <p>Every figure comes from {@link BoosterService}, which reads the systems
 * that own the bonuses, so this cannot show a number the server would not
 * apply. Inactive sources are listed with their reason rather than hidden --
 * "why am I not getting this" is the question the screen exists to answer.
 */
public final class BoostersMenu {

    private static final int OVERVIEW_SIZE = 27;
    private static final int DETAIL_SIZE = 27;
    private static final int DETAIL_BACK_SLOT = 22;
    /** Five categories centred on the middle row. */
    private static final int[] CATEGORY_SLOTS = {11, 12, 13, 14, 15};
    private static final int DETAIL_FIRST_SLOT = 10;

    private BoostersMenu() {
    }

    public static void openOverview(Player viewer, Player subject, BoosterService service, Messages messages) {
        Holder holder = new Holder(subject.getUniqueId(), subject.getName(), null);
        Inventory inventory = Bukkit.createInventory(holder, OVERVIEW_SIZE,
                viewer == subject
                        ? messages.get(viewer, "boosters.gui-title")
                        : messages.get(viewer, "boosters.gui-title-inspect", "player", subject.getName()));
        holder.inventory = inventory;

        BoosterCategory[] categories = BoosterCategory.values();
        for (int index = 0; index < categories.length && index < CATEGORY_SLOTS.length; index++) {
            BoosterCategory category = categories[index];
            inventory.setItem(CATEGORY_SLOTS[index], categoryIcon(viewer, subject, service, messages, category));
            holder.slotCategories.put(CATEGORY_SLOTS[index], category);
        }
        viewer.openInventory(inventory);
    }

    public static void openDetail(Player viewer, Player subject, BoosterService service, Messages messages,
            BoosterCategory category) {
        Holder holder = new Holder(subject.getUniqueId(), subject.getName(), category);
        Inventory inventory = Bukkit.createInventory(holder, DETAIL_SIZE,
                messages.get(viewer, "boosters.detail-title",
                        "category", messages.getRaw(viewer, "boosters.category-" + category.configKey())));
        holder.inventory = inventory;

        List<BoosterContribution> contributions = service.contributions(subject, category);
        int slot = DETAIL_FIRST_SLOT;
        for (BoosterContribution contribution : contributions) {
            if (slot >= DETAIL_FIRST_SLOT + 7) {
                break;
            }
            inventory.setItem(slot++, contributionIcon(viewer, messages, contribution));
        }
        if (contributions.isEmpty()) {
            inventory.setItem(DETAIL_FIRST_SLOT + 3, simpleIcon(Material.BARRIER,
                    messages.get(viewer, "boosters.no-sources"), List.of()));
        }
        inventory.setItem(DETAIL_BACK_SLOT, simpleIcon(Material.ARROW,
                messages.get(viewer, "boosters.back"), List.of()));
        viewer.openInventory(inventory);
    }

    private static ItemStack categoryIcon(Player viewer, Player subject, BoosterService service, Messages messages,
            BoosterCategory category) {
        BoosterStacking.Result result = service.result(subject, category);
        List<Component> lore = new ArrayList<>();
        for (BoosterContribution contribution : service.contributions(subject, category)) {
            lore.add(noItalic(contributionLine(viewer, messages, contribution)));
        }
        if (lore.isEmpty()) {
            lore.add(noItalic(messages.get(viewer, "boosters.no-sources")));
        }
        lore.add(Component.empty());
        // The raw total is only worth showing when a cap actually changed the
        // answer; otherwise it is the same number twice.
        if (result.capped()) {
            lore.add(noItalic(messages.get(viewer, "boosters.raw-total", "percent", percent(result.raw()))));
            lore.add(noItalic(messages.get(viewer, "boosters.cap",
                    "percent", percent(service.rules(category).cap()))));
        }
        lore.add(noItalic(messages.get(viewer, "boosters.effective", "percent", percent(result.effective()))));
        lore.add(Component.empty());
        lore.add(noItalic(messages.get(viewer, "boosters.click-for-detail")));

        return simpleIcon(iconMaterial(category),
                messages.get(viewer, "boosters.category-" + category.configKey()), lore);
    }

    private static ItemStack contributionIcon(Player viewer, Messages messages, BoosterContribution contribution) {
        List<Component> lore = new ArrayList<>();
        lore.add(noItalic(messages.get(viewer, "boosters.source-value",
                "percent", percent(contribution.percent()))));
        lore.add(noItalic(contribution.active()
                ? messages.get(viewer, "boosters.source-active")
                : messages.get(viewer, "boosters.source-inactive",
                        "reason", messages.getRaw(viewer, "boosters.reason-" + contribution.inactiveReasonKey()))));
        return simpleIcon(contribution.active() ? Material.LIME_DYE : Material.GRAY_DYE,
                messages.get(viewer, "boosters.source-" + contribution.sourceId()), lore);
    }

    private static Component contributionLine(Player viewer, Messages messages, BoosterContribution contribution) {
        return messages.get(viewer, contribution.active() ? "boosters.line-active" : "boosters.line-inactive",
                "source", messages.getRaw(viewer, "boosters.source-" + contribution.sourceId()),
                "percent", percent(contribution.percent()));
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

    private static ItemStack simpleIcon(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(name));
        if (!lore.isEmpty()) {
            meta.lore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    private static Component noItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    public static final class Holder implements InventoryHolder {
        private final java.util.UUID subjectUuid;
        private final String subjectName;
        private final BoosterCategory category;
        private final java.util.Map<Integer, BoosterCategory> slotCategories = new java.util.HashMap<>();
        private Inventory inventory;

        Holder(java.util.UUID subjectUuid, String subjectName, BoosterCategory category) {
            this.subjectUuid = subjectUuid;
            this.subjectName = subjectName;
            this.category = category;
        }

        public java.util.UUID subjectUuid() {
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
