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

/**
 * Opened by the host via {@code /cf review <id>} (linked from the chat
 * prompt they get the instant an opponent proposes an item match): a
 * compact 5-row GUI -- the host's own head above their wager, the
 * opponent's head above their proposed items, and a centered lime dye
 * Accept / red dye Deny on the bottom row -- with every enchant, custom
 * name, and lore intact, not flattened into chat text. Closing the GUI
 * any other way (Escape, opening something else) counts as a Deny too;
 * see {@link CoinflipMatchReviewMenuListener}.
 */
public final class CoinflipMatchReviewMenu {

    private static final int SIZE = 45;
    private static final int HOST_HEAD_SLOT = 4;
    private static final int HOST_ROW_START = 9;
    private static final int OPPONENT_HEAD_SLOT = 22;
    private static final int OPPONENT_ROW_START = 27;
    public static final int SLOT_CONFIRM = 39;
    public static final int SLOT_DENY = 41;

    private CoinflipMatchReviewMenu() {
    }

    public static void open(Player host, CoinflipManager manager, Messages messages, Coinflip coinflip, CoinflipPendingMatch match) {
        Holder holder = new Holder(coinflip.id());
        Inventory inventory = Bukkit.createInventory(holder, SIZE, messages.getGui(host, "coinflip.review-title"));
        holder.inventory = inventory;

        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, border());
        }
        inventory.setItem(HOST_HEAD_SLOT, headOf(host, host, messages));
        placeItems(inventory, HOST_ROW_START, coinflip.items());

        OfflinePlayer opponentOffline = Bukkit.getOfflinePlayer(match.opponentUuid());
        inventory.setItem(OPPONENT_HEAD_SLOT, headOf(host, opponentOffline, messages));
        placeItems(inventory, OPPONENT_ROW_START, match.items());

        inventory.setItem(SLOT_CONFIRM, confirmButton(host, messages));
        inventory.setItem(SLOT_DENY, denyButton(host, messages));

        host.openInventory(inventory);
    }

    private static void placeItems(Inventory inventory, int rowStart, ItemStack[] items) {
        for (int i = 0; i < items.length && i < 9; i++) {
            if (items[i] != null && !items[i].isEmpty()) {
                inventory.setItem(rowStart + i, items[i].clone());
            }
        }
    }

    /** {@code viewer} is always the host (whoever's locale this GUI renders in); {@code subject} is whose head/wager this is. */
    private static ItemStack headOf(Player viewer, OfflinePlayer subject, Messages messages) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(subject);
        String name = subject.getName();
        meta.displayName(noItalic(messages.getGui(viewer, "coinflip.review-whose-wager", "player", name == null ? "?" : name)));
        head.setItemMeta(meta);
        return head;
    }

    private static ItemStack confirmButton(Player viewer, Messages messages) {
        ItemStack item = new ItemStack(Material.LIME_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(messages.getGui(viewer, "coinflip.review-confirm-button")));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack denyButton(Player viewer, Messages messages) {
        ItemStack item = new ItemStack(Material.RED_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(messages.getGui(viewer, "coinflip.review-deny-button")));
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
        private final int coinflipId;
        /** Set once Accept/Deny has actually been decided, so the close handler doesn't also treat it as an (implicit) deny. */
        private boolean resolved;
        private Inventory inventory;

        Holder(int coinflipId) {
            this.coinflipId = coinflipId;
        }

        public int coinflipId() {
            return coinflipId;
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
