package me.vertex.core.collector;

import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.inventory.view.AnvilView;

/** A small, free anvil prompt used to enter a collector withdrawal amount. */
public final class CollectorWithdrawAmountMenu {

    static final int SLOT_INPUT = 0;
    static final int SLOT_RESULT = 2;
    /**
     * Vanilla considers a result whose cost is greater than or equal to this
     * value "Too Expensive".  It must therefore stay above our zero-cost
     * confirm button; using zero here disables every result slot.
     */
    static final int FREE_ANVIL_MAX_COST = 40;
    /** Stores the exact parsed amount on the result item through vanilla's rename reset. */
    private static final NamespacedKey CONFIRMED_AMOUNT_KEY = new NamespacedKey("vertex", "collector_withdraw_amount");

    private CollectorWithdrawAmountMenu() {
    }

    public static void open(Player player, Messages messages, Location location, Material material) {
        Holder holder = new Holder(location, material, messages);
        Inventory inventory = Bukkit.createInventory(holder, InventoryType.ANVIL,
                messages.get(player, "collector.withdraw-title", "item", material.name()));
        holder.inventory = inventory;

        ItemStack input = new ItemStack(material);
        ItemMeta meta = input.getItemMeta();
        // Deliberately no displayName() here: setting one makes the anvil's
        // rename box start pre-filled with that exact text, which the
        // player then has to select-all and clear before typing a number.
        // Leaving the name unset makes vanilla start the box blank, so
        // typing a number works immediately. The instruction still shows
        // up in the item's tooltip as lore instead.
        meta.lore(java.util.List.of(noItalic(messages.get(player, "collector.withdraw-prompt"))));
        input.setItemMeta(meta);
        inventory.setItem(SLOT_INPUT, input);
        inventory.setItem(SLOT_RESULT, confirmButton(player, messages, null));
        player.openInventory(inventory);
        setFreeCost(player, inventory);
    }

    static ItemStack confirmButton(Player player, Messages messages, Long amount) {
        boolean valid = amount != null && amount > 0;
        ItemStack button = new ItemStack(valid ? Material.LIME_DYE : Material.PAPER);
        ItemMeta meta = button.getItemMeta();
        meta.displayName(noItalic(messages.get(player,
                valid ? "collector.withdraw-confirm" : "collector.withdraw-confirm-empty",
                "amount", valid ? String.format("%,d", amount) : "")));
        if (valid) {
            // Anvil rename text can be cleared before InventoryClickEvent is
            // delivered for the result slot. The clicked result ItemStack is
            // still the exact button that the player saw, so keep its parsed
            // number here instead of trying to parse a reset text field.
            meta.getPersistentDataContainer().set(CONFIRMED_AMOUNT_KEY, PersistentDataType.LONG, amount);
        }
        button.setItemMeta(meta);
        return button;
    }

    static Long confirmedAmount(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(CONFIRMED_AMOUNT_KEY, PersistentDataType.LONG);
    }

    @SuppressWarnings("removal")
    static Long readAmount(org.bukkit.inventory.InventoryView view, Inventory inventory) {
        String text;
        if (view instanceof AnvilView anvilView) {
            text = anvilView.getRenameText();
        } else if (inventory instanceof AnvilInventory anvilInventory) {
            text = anvilInventory.getRenameText();
        } else {
            return null;
        }
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.trim();
        try {
            long amount = Long.parseLong(value.replace(",", ""));
            return amount > 0 ? amount : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @SuppressWarnings("removal")
    private static void setFreeCost(Player player, Inventory inventory) {
        if (player.getOpenInventory() instanceof AnvilView view) {
            view.setRepairCost(0);
            view.setMaximumRepairCost(FREE_ANVIL_MAX_COST);
        } else if (inventory instanceof AnvilInventory anvil) {
            anvil.setRepairCost(0);
            anvil.setMaximumRepairCost(FREE_ANVIL_MAX_COST);
        }
    }

    private static Component noItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    static final class Holder implements InventoryHolder {
        private final Location location;
        private final Material material;
        private final Messages messages;
        private Inventory inventory;
        // Vanilla anvils clear their own rename-text field as part of taking
        // the result item, and that reset can land before this plugin's own
        // InventoryClickEvent handler runs -- so re-reading getRenameText()
        // at click time can see a blank field even right after
        // PrepareAnvilEvent computed and displayed a valid amount on the
        // confirm button. Cache the last amount PrepareAnvilEvent
        // successfully parsed so the click handler has a same-value
        // fallback instead of failing a withdrawal the player can see was
        // already accepted.
        private Long lastPreparedAmount;

        Holder(Location location, Material material, Messages messages) {
            this.location = location;
            this.material = material;
            this.messages = messages;
        }

        Location location() {
            return location;
        }

        Material material() {
            return material;
        }

        Messages messages() {
            return messages;
        }

        Long lastPreparedAmount() {
            return lastPreparedAmount;
        }

        void setLastPreparedAmount(Long amount) {
            this.lastPreparedAmount = amount;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
