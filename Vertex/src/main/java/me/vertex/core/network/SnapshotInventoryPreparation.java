package me.vertex.core.network;

import me.vertex.core.storage.ClaimDelivery;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import java.util.ArrayList;
import java.util.List;

/** Settles player-owned crafting/cursor input without drops; custom sessions must finish first. */
final class SnapshotInventoryPreparation {
    private SnapshotInventoryPreparation() { }
    static boolean prepare(Player player, Plugin plugin) {
        if(me.vertex.core.storage.InventoryAccess.reserved(player))return false;
        if (ClaimDelivery.hasMarkedItem(plugin, player)) return false;
        var view = player.getOpenInventory();
        var top = view.getTopInventory();
        // Never snapshot while a detached custom menu still owns items or delayed callbacks.
        if (top.getType() != InventoryType.CRAFTING && top.getType() != InventoryType.WORKBENCH) return false;
        List<ClaimDelivery.TaggedItem> input = new ArrayList<>();
        ItemStack held = player.getItemOnCursor();
        ItemStack cursor = held == null ? null : held.clone();
        if (cursor != null && !cursor.isEmpty()) input.add(new ClaimDelivery.TaggedItem("cursor", cursor));
        CraftingInventory crafting = top instanceof CraftingInventory c ? c : null;
        ItemStack[] matrix = crafting == null ? new ItemStack[0] : crafting.getMatrix();
        for (ItemStack item : matrix) if (item != null && !item.isEmpty()) input.add(new ClaimDelivery.TaggedItem("craft", item.clone()));
        if (!ClaimDelivery.canFit(player, input)) return false;
        ItemStack[] before = player.getInventory().getStorageContents();
        for (int i = 0; i < before.length; i++) if (before[i] != null) before[i] = before[i].clone();
        try {
            for (var item : input) if (!player.getInventory().addItem(item.item().clone()).isEmpty()) throw new IllegalStateException("Inventory changed during snapshot preparation");
            player.setItemOnCursor(null);
            if (crafting != null) crafting.setMatrix(new ItemStack[matrix.length]);
            player.closeInventory();
            return true;
        } catch (RuntimeException error) {
            player.getInventory().setStorageContents(before);
            player.setItemOnCursor(cursor);
            if (crafting != null) crafting.setMatrix(matrix);
            return false;
        }
    }
}
