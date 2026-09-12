package me.vertex.core.enchant;

import me.vertex.core.lang.Messages;
import me.vertex.core.menu.MenuItemTemplate;
import me.vertex.core.menu.MenuLayout;
import me.vertex.core.menu.MenuRegistry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Handles item placement, the live success-chance display, and Confirm/
 * Cancel for {@link EnchantApplyGui}.
 *
 * <p>Nothing is ever escrowed: {@link #onClose} unconditionally returns
 * whatever remains in the three input slots to the player, whether or not
 * Confirm was ever clicked -- a successful application simply means the
 * item sitting in {@link EnchantApplyGui#TARGET_SLOT} when it does is the
 * newly-enchanted one, and the physical enchant item / Lucky Gems consumed
 * by a valid attempt are already gone from their slots by then. This is
 * simpler than {@code CoinflipWagerMenu}'s escrow-vs-return split, since
 * nothing here is ever handed off to another player or a database row --
 * everything that matters already happened to the ItemStacks in place.
 */
public final class EnchantApplyGuiListener implements Listener {

    private final Plugin plugin;
    private final EnchantManager manager;
    private final Messages messages;
    private final MenuRegistry menus;

    public EnchantApplyGuiListener(Plugin plugin, EnchantManager manager, Messages messages, MenuRegistry menus) {
        this.plugin = plugin;
        this.manager = manager;
        this.messages = messages;
        this.menus = menus;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof EnchantApplyGui.Holder)) {
            return;
        }
        boolean touchesLockedSlot = event.getRawSlots().stream()
                .anyMatch(slot -> slot < event.getInventory().getSize() && !isInputSlot(slot));
        if (touchesLockedSlot) {
            event.setCancelled(true);
        } else {
            scheduleRefresh(event.getInventory());
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof EnchantApplyGui.Holder)
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        boolean clickedTop = event.getClickedInventory() != null
                && event.getClickedInventory().getHolder() instanceof EnchantApplyGui.Holder;
        if (!clickedTop) {
            scheduleRefresh(event.getInventory());
            return;
        }
        if (isInputSlot(event.getRawSlot())) {
            scheduleRefresh(event.getInventory());
            return;
        }

        event.setCancelled(true);
        MenuLayout layout = menus.layout(EnchantApplyGui.MENU_ID);
        if (matches(layout, "confirm", event.getSlot())) {
            layout.playSound(player, "confirm");
            confirm(player, event.getInventory(), layout);
        } else if (matches(layout, "cancel", event.getSlot())) {
            layout.playSound(player, "cancel");
            player.closeInventory();
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof EnchantApplyGui.Holder)
                || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        int[] slots = {EnchantApplyGui.TARGET_SLOT, EnchantApplyGui.ENCHANT_SLOT, EnchantApplyGui.GEM_SLOT};
        java.util.List<ItemStack> returns = new java.util.ArrayList<>();
        for (int slot : slots) {
            ItemStack item = event.getInventory().getItem(slot);
            if (item != null && !item.isEmpty()) returns.add(item.clone());
        }
        if (returns.isEmpty()) return;
        if (!me.vertex.core.storage.DeliveryManager.queueOverflow(
                plugin, player, returns, "enchant-gui-return")) {
            player.sendMessage(messages.get(player, "delivery.storage-unavailable"));
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) player.openInventory(event.getInventory());
            });
            return;
        }
        for (int slot : slots) {
            event.getInventory().setItem(slot, null);
        }
    }

    private void confirm(Player player, Inventory inventory, MenuLayout layout) {
        ItemStack target = inventory.getItem(EnchantApplyGui.TARGET_SLOT);
        ItemStack enchantItem = inventory.getItem(EnchantApplyGui.ENCHANT_SLOT);
        if (target == null || target.isEmpty() || enchantItem == null || !manager.isEnchantItem(enchantItem)) {
            player.sendMessage(messages.get(player, "enchant.apply-missing-items"));
            return;
        }
        ItemStack gems = inventory.getItem(EnchantApplyGui.GEM_SLOT);
        int gemCount = gems != null && manager.isLuckyGem(gems) ? gems.getAmount() : 0;

        EnchantManager.ApplyOutcome outcome = manager.applyEnchant(target, enchantItem, gemCount, Math.random());
        if (outcome.consumedEnchantItem()) {
            int remaining = enchantItem.getAmount() - 1;
            inventory.setItem(EnchantApplyGui.ENCHANT_SLOT, remaining <= 0 ? null : withAmount(enchantItem, remaining));
        }
        if (outcome.consumedGems() && gems != null) {
            inventory.setItem(EnchantApplyGui.GEM_SLOT, null);
        }
        player.sendMessage(messages.get(player, messageKeyFor(outcome.result()), "enchant",
                displayName(outcome.enchantId()), "level", String.valueOf(outcome.level())));
        refreshChanceDisplay(inventory, layout);
    }

    private String displayName(String enchantId) {
        EnchantDefinition definition = manager.definition(enchantId);
        return definition == null ? String.valueOf(enchantId) : definition.displayName();
    }

    private static ItemStack withAmount(ItemStack item, int amount) {
        ItemStack clone = item.clone();
        clone.setAmount(amount);
        return clone;
    }

    private static String messageKeyFor(EnchantManager.ApplyResult result) {
        return switch (result) {
            case SUCCESS -> "enchant.apply-success";
            case FAILURE -> "enchant.apply-failure";
            case REJECT_INCOMPATIBLE -> "enchant.apply-incompatible";
            case REJECT_EQUAL_LEVEL -> "enchant.apply-equal-level";
            case REJECT_HIGHER_EXISTS -> "enchant.apply-higher-exists";
            case REJECT_INVALID -> "enchant.apply-missing-items";
        };
    }

    private static boolean isInputSlot(int slot) {
        return slot == EnchantApplyGui.TARGET_SLOT || slot == EnchantApplyGui.ENCHANT_SLOT
                || slot == EnchantApplyGui.GEM_SLOT;
    }

    private static boolean matches(MenuLayout layout, String templateId, int slot) {
        MenuItemTemplate template = layout.item(templateId);
        if (template == null) {
            return false;
        }
        for (int candidate : template.slots()) {
            if (candidate == slot) {
                return true;
            }
        }
        return false;
    }

    private void refreshChanceDisplay(Inventory inventory, MenuLayout layout) {
        EnchantApplyGui.refreshChanceDisplay(inventory, layout, manager);
    }

    /**
     * The exact slot contents a click/drag ends up producing aren't visible
     * to this handler until Bukkit finishes applying the click itself, so
     * the live chance display is recomputed one tick later rather than
     * from this event's (still pre-click) snapshot.
     */
    private void scheduleRefresh(Inventory inventory) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!(inventory.getHolder() instanceof EnchantApplyGui.Holder)) {
                return;
            }
            refreshChanceDisplay(inventory, menus.layout(EnchantApplyGui.MENU_ID));
        });
    }
}
