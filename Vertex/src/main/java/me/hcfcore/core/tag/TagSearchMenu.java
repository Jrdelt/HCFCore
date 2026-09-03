package me.hcfcore.core.tag;

import me.hcfcore.core.lang.MessageFormatter;
import me.hcfcore.core.lang.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public final class TagSearchMenu {

    static final int SLOT_INPUT = 0;
    static final int SLOT_RESULT = 2;

    private TagSearchMenu() {
    }

    public static void open(Player player, TagManager manager, Messages messages, TagMenuState state) {
        Holder holder = new Holder(manager, messages, state);
        Inventory inventory = Bukkit.createInventory(holder, InventoryType.ANVIL, messages.get(player, "tags.search-heading"));
        holder.inventory = inventory;

        ItemStack input = new ItemStack(Material.PAPER);
        ItemMeta meta = input.getItemMeta();
        // The anvil seeds its rename field from the input item's name, so
        // this doubles as the placeholder the player types over.
        meta.displayName(noItalic(messages.get(player, "tags.search-prompt")));
        input.setItemMeta(meta);
        inventory.setItem(SLOT_INPUT, input);

        refreshResult(player, messages, inventory);

        player.openInventory(inventory);
    }

    /**
     * Puts a free, clickable confirm button in the anvil's result slot.
     *
     * <p>An anvil charges XP levels to take a renamed item, which greys
     * the result slot out for anyone without the levels -- so there was
     * no way to click through and commit a search. Searching is not a
     * repair, so the cost is zeroed and the slot is filled with a button
     * that just reports what will be searched for. The click itself is
     * cancelled by the listener, so nothing is ever really withdrawn or
     * charged; zeroing the cost is what stops the client greying it out.
     */
    static void refreshResult(Player player, Messages messages, Inventory inventory) {
        if (inventory instanceof AnvilInventory anvil) {
            anvil.setRepairCost(0);
            anvil.setMaximumRepairCost(0);
        }
        inventory.setItem(SLOT_RESULT, confirmButton(player, messages, readQuery(player, messages, inventory)));
    }

    /**
     * The confirm button shown in the result slot: the tag name that will
     * be searched for, or a prompt to type one when the field is still
     * untouched.
     */
    static ItemStack confirmButton(Player player, Messages messages, String query) {
        boolean hasQuery = query != null && !query.isBlank();
        ItemStack item = new ItemStack(hasQuery ? Material.NAME_TAG : Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(messages.get(player,
                hasQuery ? "tags.search-confirm" : "tags.search-confirm-empty",
                "query", hasQuery ? query : "")));
        meta.lore(List.of(noItalic(messages.get(player,
                hasQuery ? "tags.search-confirm-hint" : "tags.search-confirm-empty-hint"))));
        item.setItemMeta(meta);
        return item;
    }

    /**
     * The tag name the player typed, or null if they left the field at the
     * placeholder or emptied it.
     *
     * <p>The anvil seeds its rename field with the input item's display
     * name, so an untouched field reads back as the prompt text rather
     * than as empty -- which would otherwise be searched for literally
     * and match no tags at all.
     */
    @SuppressWarnings("deprecation")
    static String readQuery(Player player, Messages messages, Inventory inventory) {
        if (!(inventory instanceof AnvilInventory anvil)) {
            return null;
        }
        String text = anvil.getRenameText();
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        String prompt = MessageFormatter.plain(messages.getRaw(player, "tags.search-prompt")).trim();
        return trimmed.equalsIgnoreCase(prompt) ? null : trimmed;
    }

    private static Component noItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    public static final class Holder implements InventoryHolder {
        private final TagManager manager;
        private final Messages messages;
        private final TagMenuState state;
        private Inventory inventory;
        /**
         * Set once the search has been committed by clicking the confirm
         * button, so the close that follows (reopening the tags menu
         * replaces this inventory) doesn't apply the same query a second
         * time.
         */
        private boolean committed;

        Holder(TagManager manager, Messages messages, TagMenuState state) {
            this.manager = manager;
            this.messages = messages;
            this.state = state;
        }

        public TagManager manager() {
            return manager;
        }

        public Messages messages() {
            return messages;
        }

        public TagMenuState state() {
            return state;
        }

        boolean committed() {
            return committed;
        }

        void markCommitted() {
            this.committed = true;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
