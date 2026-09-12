package me.vertex.core.enchant;

import me.vertex.core.lang.Messages;
import me.vertex.core.menu.MenuRegistry;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

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
    private final NamespacedKey luckyGemCountKey;

    public RuneListener(EnchantManager manager, Messages messages, MenuRegistry menus) {
        this.manager = manager;
        this.messages = messages;
        this.menus = menus;
        this.luckyGemCountKey = new NamespacedKey(manager.plugin(), "drag_lucky_gem_count");
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
            player.sendMessage(messages.get(player, "rune.drag-apply"));
        }
    }

    /** Direct inventory flow: Lucky Gem onto enchant, then enchant onto equipment. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
                || !(event.getClickedInventory() instanceof PlayerInventory)) {
            return;
        }
        ItemStack cursor = event.getCursor();
        ItemStack clicked = event.getCurrentItem();
        if (manager.isLuckyGem(cursor) && manager.isEnchantItem(clicked)) {
            event.setCancelled(true);
            ItemStack upgraded = withLuckyGem(clicked, luckyGemCount(clicked) + 1);
            if (clicked.getAmount() > 1) {
                event.setCurrentItem(decrease(clicked));
                give(player, upgraded);
            } else {
                event.setCurrentItem(upgraded);
            }
            event.setCursor(decrease(cursor));
            player.sendMessage(messages.get(player, "rune.lucky-gem-added"));
            return;
        }
        if (!manager.isEnchantItem(cursor) || clicked == null || clicked.getType().isAir()) {
            return;
        }
        event.setCancelled(true);
        EnchantManager.ApplyOutcome outcome = manager.applyEnchant(clicked, cursor, luckyGemCount(cursor), Math.random());
        if (outcome.result() == EnchantManager.ApplyResult.SUCCESS) {
            event.setCurrentItem(clicked);
        }
        if (outcome.consumedEnchantItem()) {
            event.setCursor(decrease(cursor));
        }
        EnchantDefinition definition = manager.definition(outcome.enchantId());
        String name = definition == null ? String.valueOf(outcome.enchantId()) : definition.displayName();
        String key = switch (outcome.result()) {
            case SUCCESS -> "enchant.apply-success";
            case FAILURE -> "enchant.apply-failure";
            case REJECT_INCOMPATIBLE -> "enchant.apply-incompatible";
            case REJECT_EQUAL_LEVEL -> "enchant.apply-equal-level";
            case REJECT_HIGHER_EXISTS -> "enchant.apply-higher-exists";
            case REJECT_INVALID -> "enchant.apply-missing-items";
        };
        player.sendMessage(messages.get(player, key, "enchant", name, "level", String.valueOf(outcome.level())));
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

    private int luckyGemCount(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        Integer count = item.getItemMeta().getPersistentDataContainer().get(luckyGemCountKey, PersistentDataType.INTEGER);
        return count == null ? 0 : Math.max(0, count);
    }

    private ItemStack withLuckyGem(ItemStack item, int count) {
        ItemStack upgraded = item.clone();
        upgraded.setAmount(1);
        ItemMeta meta = upgraded.getItemMeta();
        meta.getPersistentDataContainer().set(luckyGemCountKey, PersistentDataType.INTEGER, count);
        List<Component> lore = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
        lore.removeIf(line -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(line).startsWith("Lucky Gems applied:"));
        lore.add(Component.text("Lucky Gems applied: " + count));
        meta.lore(lore);
        upgraded.setItemMeta(meta);
        return upgraded;
    }

    private static ItemStack decrease(ItemStack item) {
        if (item == null || item.getAmount() <= 1) return null;
        ItemStack result = item.clone();
        result.setAmount(result.getAmount() - 1);
        return result;
    }

    private static void give(Player player, ItemStack item) {
        player.getInventory().addItem(item).values()
                .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
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
