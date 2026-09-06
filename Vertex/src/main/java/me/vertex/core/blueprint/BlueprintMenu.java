package me.vertex.core.blueprint;

import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/** Opened by right-clicking an in-progress blueprint's beacon: progress info and a cancel button. */
public final class BlueprintMenu {

    public static final int CANCEL_SLOT = 8;

    private BlueprintMenu() {
    }

    public static void open(Player player, Messages messages, ActiveBuild build) {
        Holder holder = new Holder(build.id());
        Inventory inventory = Bukkit.createInventory(holder, 9, messages.get(player, "blueprint.menu-title"));
        holder.inventory = inventory;

        int total = build.blocks() == null ? 0 : build.blocks().size();
        int done = build.currentIndex();
        long elapsedSeconds = (System.currentTimeMillis() - build.startedAtMillis()) / 1000;

        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.displayName(MessageFormatter.deserialize(build.template().displayName()));
        infoMeta.lore(List.of(
                messages.get(player, "blueprint.menu-progress", "done", String.valueOf(done), "total", String.valueOf(total)),
                messages.get(player, "blueprint.menu-elapsed", "seconds", String.valueOf(elapsedSeconds))));
        info.setItemMeta(infoMeta);
        inventory.setItem(4, info);

        ItemStack cancel = new ItemStack(Material.BARRIER);
        ItemMeta cancelMeta = cancel.getItemMeta();
        cancelMeta.displayName(MessageFormatter.deserialize("<red>Cancel Build"));
        cancelMeta.lore(List.of(messages.get(player, "blueprint.menu-cancel-lore")));
        cancel.setItemMeta(cancelMeta);
        inventory.setItem(CANCEL_SLOT, cancel);

        player.openInventory(inventory);
    }

    public static final class Holder implements InventoryHolder {
        private final int buildId;
        private Inventory inventory;

        private Holder(int buildId) {
            this.buildId = buildId;
        }

        public int buildId() {
            return buildId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
