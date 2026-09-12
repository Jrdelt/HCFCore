package me.vertex.core.enchant;

import me.vertex.core.lang.Messages;
import me.vertex.core.menu.MenuRegistry;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Two unrelated jobs sharing one class because both are "make a Rune/
 * enchant item behave correctly with no GUI in front of it":
 *
 * <ol>
 *   <li>Right-click-to-roll (section 12) and right-click-to-open-the-
 *   apply-GUI (section 15). Rolling a Rune is a direct item interaction
 *   with a result message -- re-reading section 12 ("right-clicking a
 *   Rune... create the physical enchantment item, give/store it") against
 *   section 15 ("players apply physical enchantment items through the
 *   custom GUI/system"), only application goes through a GUI; rolling does
 *   not. See {@code EnchantApplyGui} for the GUI half.
 *   <li>{@link PrepareAnvilEvent}/{@link PrepareSmithingEvent} persistence
 *   hooks (section 23) -- genuinely new integration surface with no
 *   precedent elsewhere in this codebase. The actual data-preservation
 *   logic lives in and is unit-tested via {@code
 *   EnchantManager#preserveAcrossTransform}; these two handlers are thin
 *   plumbing around it and should get a manual smoke test on a real
 *   server (anvil rename, anvil repair-combine, netherite upgrade) before
 *   shipping, since MockBukkit has no practical way to fire these events
 *   with a real computed result to assert against.
 * </ol>
 */
public final class RuneListener implements Listener {

    private final EnchantManager manager;
    private final Messages messages;
    private final MenuRegistry menus;

    public RuneListener(EnchantManager manager, Messages messages, MenuRegistry menus) {
        this.manager = manager;
        this.messages = messages;
        this.menus = menus;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack inHand = event.getItem();

        RuneTier tier = manager.tierOf(inHand);
        if (tier != null) {
            event.setCancelled(true);
            rollRune(player, inHand, tier);
            return;
        }
        if (manager.isEnchantItem(inHand)) {
            event.setCancelled(true);
            openApplyGui(player, inHand);
        }
    }

    private void rollRune(Player player, ItemStack rune, RuneTier tier) {
        EnchantManager.RollOutcome outcome = manager.rollRune(tier, Math.random());
        if (!outcome.ok()) {
            player.sendMessage(messages.get(player, "rune.table-empty"));
            return;
        }

        // The rolled item is durably admitted before consuming the Rune.
        if (!me.vertex.core.storage.DeliveryManager.queueOverflow(manager.plugin(), player,
                java.util.List.of(outcome.createdItem()), "rune-roll")) {
            player.sendMessage(messages.get(player, "delivery.storage-unavailable"));
            return;
        }
        int remaining = rune.getAmount() - 1;
        if (remaining <= 0) {
            player.getInventory().setItemInMainHand(null);
        } else {
            rune.setAmount(remaining);
        }
        EnchantDefinition definition = manager.definition(outcome.enchantId());
        String enchantName = definition == null ? outcome.enchantId() : definition.displayName();
        player.sendMessage(messages.get(player, "rune.rolled", "enchant", enchantName,
                "level", String.valueOf(outcome.level())));
    }

    private void openApplyGui(Player player, ItemStack enchantItem) {
        ItemStack moved = enchantItem.clone();
        moved.setAmount(1);
        int remaining = enchantItem.getAmount() - 1;
        if (remaining <= 0) {
            player.getInventory().setItemInMainHand(null);
        } else {
            enchantItem.setAmount(remaining);
        }
        EnchantApplyGui.open(player, manager, messages, menus, moved);
    }

    // ------------------------------------------------------------------
    // Persistence across legitimate vanilla transformations (section 23)
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        ItemStack result = event.getResult();
        if (result == null || result.getType().isAir()) {
            return;
        }
        ItemStack left = event.getInventory().getItem(0);
        manager.preserveAcrossTransform(left, result);
        event.setResult(result);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        ItemStack result = event.getResult();
        if (result == null || result.getType().isAir()) {
            return;
        }
        ItemStack base = event.getInventory().getInputEquipment();
        manager.preserveAcrossTransform(base, result);
        event.setResult(result);
    }
}
