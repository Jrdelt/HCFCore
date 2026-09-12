package me.vertex.core.coinflip;

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

import java.util.List;
import java.util.UUID;

/**
 * Where a player picks the items they're wagering -- for hosting a new
 * item coinflip ({@code /cf hand}), or for matching someone else's when
 * clicking Play on one. A compact 3-row GUI: row 0 is filler, row 1 (9
 * slots) is where items go -- matching {@code max-item-stacks-per-wager}'s
 * default of 9 -- and row 2 holds a centered Confirm/Cancel. Closing
 * without confirming, or clicking Cancel, hands back everything placed so
 * far -- nothing is escrowed until Confirm actually succeeds.
 */
public final class CoinflipWagerMenu {

    public static final int ROWS = 3;
    public static final int GRID_START = 9;
    public static final int GRID_SLOTS = 9; // row 1 only
    public static final int SLOT_CONFIRM = 21;
    public static final int SLOT_CANCEL = 23;

    public enum Mode {
        CREATE, JOIN
    }

    private CoinflipWagerMenu() {
    }

    public static void openForCreate(Player player, Messages messages, UUID targetUuid) {
        open(player, messages, new Holder(Mode.CREATE, targetUuid, -1));
    }

    public static void openForJoin(Player player, Messages messages, int coinflipId) {
        open(player, messages, new Holder(Mode.JOIN, null, coinflipId));
    }

    private static void open(Player player, Messages messages, Holder holder) {
        Inventory inventory = Bukkit.createInventory(holder, ROWS * 9, messages.getGui(player, "coinflip.wager-title"));
        holder.inventory = inventory;
        for (int slot = 0; slot < GRID_START; slot++) {
            inventory.setItem(slot, border());
        }
        for (int slot = GRID_START + GRID_SLOTS; slot < ROWS * 9; slot++) {
            inventory.setItem(slot, border());
        }
        inventory.setItem(SLOT_CONFIRM, confirmButton(player, messages));
        inventory.setItem(SLOT_CANCEL, cancelButton(player, messages));
        player.openInventory(inventory);
    }

    private static ItemStack confirmButton(Player player, Messages messages) {
        ItemStack item = new ItemStack(Material.LIME_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(messages.getGui(player, "coinflip.wager-confirm-button")));
        meta.lore(List.of(noItalic(messages.getGui(player, "coinflip.wager-confirm-lore"))));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack cancelButton(Player player, Messages messages) {
        ItemStack item = new ItemStack(Material.RED_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(messages.getGui(player, "coinflip.wager-cancel-button")));
        meta.lore(List.of(noItalic(messages.getGui(player, "coinflip.wager-cancel-lore"))));
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

    private static Component noItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    public static final class Holder implements InventoryHolder {
        private final Mode mode;
        private final UUID targetUuid;
        private final int joiningCoinflipId;
        /** Set once Confirm has actually succeeded, so the close handler doesn't also return the (now escrowed) items. */
        private boolean resolved;
        private Inventory inventory;

        Holder(Mode mode, UUID targetUuid, int joiningCoinflipId) {
            this.mode = mode;
            this.targetUuid = targetUuid;
            this.joiningCoinflipId = joiningCoinflipId;
        }

        public Mode mode() {
            return mode;
        }

        public UUID targetUuid() {
            return targetUuid;
        }

        public int joiningCoinflipId() {
            return joiningCoinflipId;
        }

        public boolean isResolved() {
            return resolved;
        }

        public void markResolved() {
            resolved = true;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
