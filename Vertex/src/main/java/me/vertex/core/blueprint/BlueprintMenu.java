package me.vertex.core.blueprint;

import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.List;

/**
 * Handles in-progress build progress/cancellation as well as
 * completed blueprint status and missing-block repair actions.
 */
public final class BlueprintMenu {

    public static final int RESUME_SLOT = 0;
    public static final int CANCEL_SLOT = 8;
    public static final int REPAIR_SLOT = 4;

    private BlueprintMenu() {
    }

    /** Opened when the blueprint is still actively building. */
    public static void openActive(Player player, BlueprintManager manager, Messages messages, ActiveBuild build) {
        Holder holder = new Holder(build.id(), build.anchor(), build.template().name(), null);
        Inventory inventory = Bukkit.createInventory(holder, 9, messages.get(player, "blueprint.gui-active-title"));
        holder.inventory = inventory;

        int total = build.blocks() == null ? 0 : build.blocks().size();
        int done = build.currentIndex();
        long remainingSeconds = manager.remainingBuildSeconds(build);

        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.displayName(MessageFormatter.deserialize(build.template().displayName()));
        infoMeta.lore(build.isPaused()
                ? List.of(
                        messages.get(player, "blueprint.menu-progress", "done", String.valueOf(done), "total",
                                String.valueOf(total)),
                        messages.get(player, "blueprint.menu-remaining", "seconds", String.valueOf(remainingSeconds)),
                        messages.get(player, "blueprint.menu-paused"))
                : List.of(
                        messages.get(player, "blueprint.menu-progress", "done", String.valueOf(done), "total",
                                String.valueOf(total)),
                        messages.get(player, "blueprint.menu-remaining", "seconds", String.valueOf(remainingSeconds))));
        info.setItemMeta(infoMeta);
        inventory.setItem(4, info);

        if (build.isPaused()) {
            ItemStack resume = new ItemStack(Material.LIME_DYE);
            ItemMeta resumeMeta = resume.getItemMeta();
            resumeMeta.displayName(messages.get(player, "blueprint.gui-resume-button"));
            resumeMeta.lore(List.of(messages.get(player, "blueprint.menu-resume-lore")));
            resume.setItemMeta(resumeMeta);
            inventory.setItem(RESUME_SLOT, resume);
        }

        ItemStack cancel = new ItemStack(Material.BARRIER);
        ItemMeta cancelMeta = cancel.getItemMeta();
        cancelMeta.displayName(messages.get(player, "blueprint.gui-cancel-button"));
        cancelMeta.lore(List.of(messages.get(player, "blueprint.menu-cancel-lore")));
        cancel.setItemMeta(cancelMeta);
        inventory.setItem(CANCEL_SLOT, cancel);

        player.openInventory(inventory);
    }

    /** Opened when right-clicking a finished blueprint beacon. */
    public static void openCompleted(Player player, BlueprintManager manager, Messages messages, Location anchor,
            BlueprintTemplate template, List<ActiveBuild.PendingBlock> missingBlocks) {
        Holder holder = new Holder(-1, anchor, template.name(), missingBlocks);
        Inventory inventory = Bukkit.createInventory(holder, 9, messages.get(player, "blueprint.gui-status-title"));
        holder.inventory = inventory;

        int totalTasks = missingBlocks.size();
        int bpt = manager.blocksPerTick(template, totalTasks);
        int interval = manager.batchInterval(template, totalTasks);

        long durationSeconds;
        if (totalTasks == 0 || bpt <= 0) {
            durationSeconds = 0;
        } else {
            long batches = (totalTasks + (long) bpt - 1L) / bpt;
            long totalTicks = batches * interval;
            durationSeconds = (totalTicks + 19L) / 20L;
        }

        ItemStack diamond = new ItemStack(Material.DIAMOND);
        ItemMeta meta = diamond.getItemMeta();
        meta.displayName(noItalic(messages.get(player, "blueprint.gui-repair-button")));

        Component statusComponent = noItalic(messages.get(player,
                totalTasks == 0 ? "blueprint.gui-repair-intact" : "blueprint.gui-repair-action"));

        meta.lore(List.of(
                noItalic(messages.get(player, "blueprint.gui-repair-lore-template", "template",
                        template.displayName())),
                noItalic(messages.get(player, "blueprint.gui-repair-lore-missing", "missing",
                        String.valueOf(totalTasks))),
                noItalic(messages.get(player, "blueprint.gui-repair-lore-time", "seconds",
                        String.valueOf(durationSeconds))),
                Component.empty().decoration(TextDecoration.ITALIC, false),
                statusComponent));

        diamond.setItemMeta(meta);
        inventory.setItem(REPAIR_SLOT, diamond);

        player.openInventory(inventory);
    }

    private static Component noItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    public static final class Holder implements InventoryHolder {
        private final int buildId;
        private final Location anchor;
        private final String templateName;
        private final List<ActiveBuild.PendingBlock> missingBlocks;
        private Inventory inventory;

        Holder(int buildId, Location anchor, String templateName, List<ActiveBuild.PendingBlock> missingBlocks) {
            this.buildId = buildId;
            this.anchor = anchor == null ? null : anchor.clone();
            this.templateName = templateName;
            this.missingBlocks = missingBlocks;
        }

        public int buildId() {
            return buildId;
        }

        public Location anchor() {
            return anchor == null ? null : anchor.clone();
        }

        public String templateName() {
            return templateName;
        }

        public List<ActiveBuild.PendingBlock> missingBlocks() {
            return missingBlocks;
        }

        public boolean isCompletedView() {
            return buildId == -1;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
