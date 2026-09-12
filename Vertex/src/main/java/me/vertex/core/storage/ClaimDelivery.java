package me.vertex.core.storage;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Marks the tiny database-to-inventory handoff window with durable item data.
 * A restart can see which reserved rows already reached the inventory instead
 * of blindly paying them a second time.
 */
public final class ClaimDelivery {
    private ClaimDelivery() {
    }

    public record TaggedItem(String marker, ItemStack item) { }

    public static NamespacedKey key(Plugin plugin) {
        return new NamespacedKey(plugin, "claim_delivery");
    }

    public static TaggedItem tagged(Plugin plugin, String source, String token, long rowId, int itemIndex,
            ItemStack sourceItem) {
        return tagged(plugin,source,token,String.valueOf(rowId),itemIndex,sourceItem);
    }

    public static TaggedItem tagged(Plugin plugin,String source,String token,String rowId,int itemIndex,
            ItemStack sourceItem){
        String marker = source + ":" + token + ":" + rowId + ":" + itemIndex;
        ItemStack item = sourceItem.clone();
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(key(plugin), PersistentDataType.STRING, marker);
        item.setItemMeta(meta);
        return new TaggedItem(marker, item);
    }

    /** Returns only expected stacks not already present from an interrupted delivery. */
    public static List<TaggedItem> missing(Player player, Plugin plugin, List<TaggedItem> expected) {
        Map<String, Integer> present = new HashMap<>();
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.isEmpty() || !item.hasItemMeta()) continue;
            String marker = item.getItemMeta().getPersistentDataContainer().get(key(plugin), PersistentDataType.STRING);
            if (marker != null) present.merge(marker, item.getAmount(), Integer::sum);
        }
        List<TaggedItem> missing = new ArrayList<>();
        for (TaggedItem item : expected) {
            int found = present.getOrDefault(item.marker(), 0);
            if (found >= item.item().getAmount()) {
                present.put(item.marker(), found - item.item().getAmount());
            } else {
                ItemStack remainder = item.item().clone();
                remainder.setAmount(item.item().getAmount() - found);
                missing.add(new TaggedItem(item.marker(), remainder));
                present.remove(item.marker());
            }
        }
        return missing;
    }

    /** Simulates Bukkit's storage-inventory stacking before anything is changed. */
    public static boolean canFit(Player player, List<TaggedItem> items) {
        ItemStack[] contents = player.getInventory().getStorageContents().clone();
        for (TaggedItem tagged : items) {
            ItemStack need = tagged.item();
            int remaining = need.getAmount();
            for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
                ItemStack have = contents[slot];
                if (have == null || have.isEmpty() || !have.isSimilar(need)) continue;
                int moved = Math.min(have.getMaxStackSize() - have.getAmount(), remaining);
                if (moved <= 0) continue;
                ItemStack changed = have.clone();
                changed.setAmount(have.getAmount() + moved);
                contents[slot] = changed;
                remaining -= moved;
            }
            for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
                ItemStack have = contents[slot];
                if (have != null && !have.isEmpty()) continue;
                int moved = Math.min(need.getMaxStackSize(), remaining);
                ItemStack placed = need.clone();
                placed.setAmount(moved);
                contents[slot] = placed;
                remaining -= moved;
            }
            if (remaining > 0) return false;
        }
        return true;
    }

    /** Adds a preflight-checked batch. Any leftover is a hard invariant failure. */
    public static boolean add(Player player, List<TaggedItem> items) {
        if (!canFit(player, items)) return false;
        ItemStack[] before = cloneContents(player.getInventory().getStorageContents());
        for (TaggedItem item : items) {
            if (!player.getInventory().addItem(item.item().clone()).isEmpty()) {
                player.getInventory().setStorageContents(before);
                return false;
            }
        }
        return true;
    }

    private static ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int slot = 0; slot < contents.length; slot++) {
            copy[slot] = contents[slot] == null ? null : contents[slot].clone();
        }
        return copy;
    }

    public static void clearMarkers(Player player, Plugin plugin, String source, String token) {
        String prefix = source + ":" + token + ":";
        NamespacedKey key = key(plugin);
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.isEmpty() || !item.hasItemMeta()) continue;
            ItemMeta meta = item.getItemMeta();
            String marker = meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
            if (marker == null || !marker.startsWith(prefix)) continue;
            meta.getPersistentDataContainer().remove(key);
            item.setItemMeta(meta);
            player.getInventory().setItem(slot, item);
        }
    }

    public static void clearMarker(Player player, Plugin plugin, String acknowledgedMarker) {
        ItemStack[] contents=player.getInventory().getContents();
        for(int slot=0;slot<contents.length;slot++){
            ItemStack item=contents[slot];if(item==null||!item.hasItemMeta())continue;
            ItemMeta meta=item.getItemMeta();
            if(!acknowledgedMarker.equals(meta.getPersistentDataContainer().get(key(plugin),PersistentDataType.STRING)))continue;
            meta.getPersistentDataContainer().remove(key(plugin));item.setItemMeta(meta);player.getInventory().setItem(slot,item);
        }
    }

    /** Cleans acknowledged markers left behind by a disconnect after commit. */
    public static void clearSourceMarkers(Player player, Plugin plugin, String source) {
        String prefix = source + ":";
        NamespacedKey key = key(plugin);
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.isEmpty() || !item.hasItemMeta()) continue;
            ItemMeta meta = item.getItemMeta();
            String marker = meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
            if (marker == null || !marker.startsWith(prefix)) continue;
            meta.getPersistentDataContainer().remove(key);
            item.setItemMeta(meta);
        }
    }

    public static boolean isMarked(Plugin plugin, ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(key(plugin), PersistentDataType.STRING);
    }

    public static boolean hasMarkedItem(Plugin plugin, Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (isMarked(plugin, item)) return true;
        }
        return false;
    }
}
