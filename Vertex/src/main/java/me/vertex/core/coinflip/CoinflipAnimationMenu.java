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
 * coinflip to whichever of the two players has animations enabled and is
 * online: a single reel slot alternates between the host's and the
 * opponent's head, slowing down each flip, and finally lands on the
 * winner's head. Purely cosmetic -- the flip already resolved (money,
 * levels, or items already moved) before this ever opens, so closing it
 * early, or not seeing it at all, changes nothing about the outcome.
 */
public final class CoinflipAnimationMenu {

    private static final int SIZE = 9;
    private static final int SLOT_REEL = 4;
    /** Ticks between each flip, front-loaded fast then slowing down before landing on the last entry. */
    private static final long[] FLIP_DELAYS = {2L, 2L, 3L, 3L, 4L, 5L, 6L, 8L, 10L, 12L};
    private static final long HOLD_TICKS_BEFORE_CLOSE = 30L;

    private CoinflipAnimationMenu() {
    }

    /**
     * {@code onResultShown} fires the instant the reel lands on the final
     * head -- not before, and not only once the GUI later auto-closes --
     * so a caller can defer revealing the winner (e.g. a chat message)
     * until the animation has actually shown it, instead of spoiling it
     * up front.
     */
    public static void play(Plugin plugin, Player viewer, Messages messages, OfflinePlayer host, OfflinePlayer opponent,
            boolean hostWon, Runnable onResultShown) {
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

        runFlip(plugin, viewer, inventory, hostHead, opponentHead, hostWon, 0, onResultShown);
    }

    private static void runFlip(Plugin plugin, Player viewer, Inventory inventory, ItemStack hostHead,
            ItemStack opponentHead, boolean hostWon, int flipIndex, Runnable onResultShown) {
        if (!viewer.isOnline() || !isStillShowing(viewer, inventory)) {
            // Closed early (or logged off) before landing -- reveal now rather than never.
            onResultShown.run();
            return;
        }
        boolean isLast = flipIndex >= FLIP_DELAYS.length;
        inventory.setItem(SLOT_REEL, isLast ? (hostWon ? hostHead : opponentHead)
                : (flipIndex % 2 == 0 ? opponentHead : hostHead));

        if (isLast) {
            onResultShown.run();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (viewer.isOnline() && isStillShowing(viewer, inventory)) {
                    viewer.closeInventory();
                }
            }, HOLD_TICKS_BEFORE_CLOSE);
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> runFlip(plugin, viewer, inventory, hostHead, opponentHead, hostWon, flipIndex + 1, onResultShown),
                FLIP_DELAYS[flipIndex]);
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
