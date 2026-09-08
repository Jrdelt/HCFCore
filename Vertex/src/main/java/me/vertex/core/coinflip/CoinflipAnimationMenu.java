package me.vertex.core.coinflip;

import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

/**
 * A one-row "slot machine" result animation, shown once per resolved
 * coinflip to each online participant at the same time: a single reel slot
 * alternates between the host's and the opponent's head, slowing down before
 * it lands on the winner's head. Purely cosmetic -- the server has already
 * chosen and durably recorded the result before this ever opens, so closing
 * it early changes neither the result nor the payout.
 */
public final class CoinflipAnimationMenu {

    private static final int SIZE = 9;
    private static final int SLOT_REEL = 4;
    /** Relative frame weights: rapid at first, then slower near the landing. */
    private static final int[] FLIP_WEIGHTS = {1, 1, 1, 1, 1, 1, 2, 2, 2, 3, 3, 4, 5, 6};
    private static final long HOLD_TICKS_BEFORE_CLOSE = 30L;

    private CoinflipAnimationMenu() {
    }

    public static void play(Plugin plugin, Player viewer, Messages messages, OfflinePlayer host, OfflinePlayer opponent,
            boolean hostWon, long durationTicks) {
        Holder holder = new Holder();
        Inventory inventory = Bukkit.createInventory(holder, SIZE, messages.get(viewer, "coinflip.animation-gui-title"));
        holder.inventory = inventory;
        for (int slot = 0; slot < SIZE; slot++) {
            if (slot == SLOT_REEL - 1 || slot == SLOT_REEL + 1) {
                inventory.setItem(slot, pointer());
            } else if (slot != SLOT_REEL) {
                inventory.setItem(slot, border());
            }
        }
        ItemStack hostHead = headOf(host);
        ItemStack opponentHead = headOf(opponent);
        inventory.setItem(SLOT_REEL, hostHead);
        viewer.openInventory(inventory);

        runFlip(plugin, viewer, inventory, hostHead, opponentHead, hostWon, 0, flipDelays(durationTicks));
    }

    private static void runFlip(Plugin plugin, Player viewer, Inventory inventory, ItemStack hostHead,
            ItemStack opponentHead, boolean hostWon, int flipIndex, long[] flipDelays) {
        if (!viewer.isOnline() || !isStillShowing(viewer, inventory)) {
            return;
        }
        boolean isLast = flipIndex >= flipDelays.length;
        inventory.setItem(SLOT_REEL, isLast ? (hostWon ? hostHead : opponentHead)
                : (flipIndex % 2 == 0 ? opponentHead : hostHead));

        if (isLast) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (viewer.isOnline() && isStillShowing(viewer, inventory)) {
                    viewer.closeInventory();
                }
            }, HOLD_TICKS_BEFORE_CLOSE);
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> runFlip(plugin, viewer, inventory, hostHead, opponentHead, hostWon, flipIndex + 1, flipDelays),
                flipDelays[flipIndex]);
    }

    /**
     * Produces delays that always sum to the server-selected duration, rather
     * than relying on a hard-coded reel which can drift away from the config.
     */
    private static long[] flipDelays(long durationTicks) {
        long totalWeight = 0;
        for (int weight : FLIP_WEIGHTS) {
            totalWeight += weight;
        }
        long[] delays = new long[FLIP_WEIGHTS.length];
        long previousOffset = 0;
        long accumulatedWeight = 0;
        for (int index = 0; index < FLIP_WEIGHTS.length; index++) {
            accumulatedWeight += FLIP_WEIGHTS[index];
            long offset = Math.round(durationTicks * (accumulatedWeight / (double) totalWeight));
            delays[index] = Math.max(1L, offset - previousOffset);
            previousOffset = offset;
        }
        // Rounding each boundary can leave the schedule one or two ticks off.
        // Put the adjustment on the final, slowest frame so the visual rhythm
        // remains natural and the server-wide reveal remains synchronized.
        long scheduled = 0;
        for (long delay : delays) {
            scheduled += delay;
        }
        delays[delays.length - 1] = Math.max(1L, delays[delays.length - 1] + (durationTicks - scheduled));
        return delays;
    }

    private static boolean isStillShowing(Player viewer, Inventory inventory) {
        return viewer.getOpenInventory().getTopInventory().equals(inventory);
    }

    private static ItemStack headOf(OfflinePlayer player) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(player);
        String name = player.getName();
        meta.displayName(Component.text(name == null ? "?" : name).decoration(TextDecoration.ITALIC, false));
        head.setItemMeta(meta);
        return head;
    }

    private static ItemStack pointer() {
        ItemStack item = new ItemStack(Material.YELLOW_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty());
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack border() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty());
        item.setItemMeta(meta);
        return item;
    }

    public static final class Holder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
