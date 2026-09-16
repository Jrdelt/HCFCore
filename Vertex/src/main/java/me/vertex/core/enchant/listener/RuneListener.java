package me.vertex.core.enchant.listener;

import me.vertex.core.enchant.AutoIncineration;
import me.vertex.core.enchant.AutoIncinerationNotifier;
import me.vertex.core.enchant.EnchantDefinition;
import me.vertex.core.enchant.EnchantManager;
import me.vertex.core.enchant.IncinerationService;
import me.vertex.core.enchant.RuneFormatting;
import me.vertex.core.enchant.RuneTier;
import me.vertex.core.lang.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.function.Consumer;

/** Direct-inventory interaction path for every Rune tier. */
public final class RuneListener implements Listener {
    private final EnchantManager manager;
    private final AutoIncineration autoIncineration;
    private final IncinerationService incineration;
    private final AutoIncinerationNotifier notifier;
    private final Messages messages;

    public RuneListener(EnchantManager manager, AutoIncineration autoIncineration, IncinerationService incineration,
            AutoIncinerationNotifier notifier, Messages messages) {
        this.manager = manager;
        this.autoIncineration = autoIncineration;
        this.incineration = incineration;
        this.notifier = notifier;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }
        ItemStack item = event.getItem();
        Player player = event.getPlayer();
        RuneTier tier = manager.tierOf(item);
        if (tier != null) {
            event.setCancelled(true);
            identify(player, event.getHand(), item, tier);
            return;
        }
        if (manager.isEnchantItem(item)) {
            event.setCancelled(true);
            player.sendMessage(messages.get(player, "rune.drag-apply"));
        }
    }

    /** PDC-marked Rune cosmetics may never become blocks; normal candles are untouched. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (manager.isRune(item) || manager.isEnchantItem(item) || manager.isLuckyGem(item)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
                || !(event.getClickedInventory() instanceof PlayerInventory inventory)) {
            return;
        }
        ItemStack cursor = event.getCursor();
        ItemStack clicked = event.getCurrentItem();
        if (manager.isLuckyGem(cursor) && manager.isEnchantItem(clicked)) {
            event.setCancelled(true);
            applyGem(player, clicked, cursor, event::setCurrentItem, event::setCursor, inventory);
            return;
        }
        if (!isApplicationTarget(clicked)) {
            return;
        }
        if (manager.isEnchantItem(cursor)) {
            event.setCancelled(true);
            applyRune(player, clicked, cursor, event::setCurrentItem, event::setCursor);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlots().size() != 1) {
            return;
        }
        ItemStack cursor = event.getOldCursor();
        int rawSlot = event.getRawSlots().iterator().next();
        if (!(event.getView().getInventory(rawSlot) instanceof PlayerInventory inventory)) {
            return;
        }
        int slot = event.getView().convertSlot(rawSlot);
        ItemStack clicked = inventory.getItem(slot);
        if (manager.isLuckyGem(cursor) && manager.isEnchantItem(clicked)) {
            event.setCancelled(true);
            applyGem(player, clicked, cursor, upgraded -> inventory.setItem(slot, upgraded),
                    player::setItemOnCursor, inventory);
            return;
        }
        if (!isApplicationTarget(clicked)) {
            return;
        }
        if (manager.isEnchantItem(cursor)) {
            event.setCancelled(true);
            applyRune(player, clicked, cursor, upgraded -> inventory.setItem(slot, upgraded), player::setItemOnCursor);
        }
    }

    /**
     * Requires inventory space before consuming anything at all -- even
     * when the result is about to be immediately destroyed by
     * Auto-Incineration, per spec: a full inventory refuses the whole
     * identification rather than dropping the result on the ground (this
     * codebase's only ground-drop fallback for identification was
     * deliberately removed) or silently discarding the base Rune.
     */
    private void identify(Player player, EquipmentSlot hand, ItemStack baseRune, RuneTier tier) {
        EnchantManager.RollOutcome outcome = manager.rollRune(tier, Math.random());
        if (!outcome.ok()) {
            player.sendMessage(messages.get(player, "rune.table-empty"));
            return;
        }
        if (!hasRoomFor(player, outcome.createdItem())) {
            player.sendMessage(messages.get(player, "rune.identify-inventory-full"));
            return;
        }
        consumeHand(player, hand, baseRune);
        EnchantDefinition definition = manager.definition(outcome.enchantId());
        String name = definition == null ? outcome.enchantId()
                : RuneFormatting.coloredNameRaw(tier, definition.displayName());

        if (autoIncineration.isActive(player)
                && autoIncineration.isEligibleAndUnprotected(outcome.createdItem(), manager, player.getUniqueId())) {
            IncinerationService.Outcome burned = incineration.incinerateRolledDirect(player, outcome.createdItem());
            player.sendMessage(messages.get(player, "rune.rolled", "enchant", name, "level", RuneFormatting.roman(outcome.level())));
            if (burned.success()) {
                notifier.notifyIncinerated(player, name, outcome.level(), burned.currency(), burned.rewardAmount());
            }
            return;
        }

        player.getInventory().addItem(outcome.createdItem());
        player.sendMessage(messages.get(player, "rune.rolled", "enchant", name, "level", RuneFormatting.roman(outcome.level())));
    }

    private void applyGem(Player player, ItemStack clicked, ItemStack cursor,
            Consumer<ItemStack> targetSetter, Consumer<ItemStack> cursorSetter, PlayerInventory inventory) {
        ItemStack upgraded = manager.addLuckyGem(clicked);
        if (upgraded == null) {
            player.sendMessage(messages.get(player, "rune.success-capped"));
            return;
        }
        if (!replaceStackedRune(player, clicked, upgraded, targetSetter, inventory)) {
            return;
        }
        cursorSetter.accept(decrease(cursor));
        player.sendMessage(messages.get(player, "rune.lucky-gem-added"));
    }

    private boolean replaceStackedRune(Player player, ItemStack clicked, ItemStack upgraded,
            Consumer<ItemStack> targetSetter, PlayerInventory inventory) {
        if (clicked.getAmount() <= 1) {
            targetSetter.accept(upgraded);
            return true;
        }
        if (!hasRoomFor(player, upgraded)) {
            player.sendMessage(messages.get(player, "rune.gem-inventory-full"));
            return false;
        }
        targetSetter.accept(withAmount(clicked, clicked.getAmount() - 1));
        inventory.addItem(upgraded);
        return true;
    }

    private void applyRune(Player player, ItemStack target, ItemStack rune,
            Consumer<ItemStack> targetSetter, Consumer<ItemStack> cursorSetter) {
        EnchantManager.ApplyOutcome outcome = manager.applyEnchant(target, rune, 0, Math.random());
        if (outcome.result() == EnchantManager.ApplyResult.SUCCESS) {
            targetSetter.accept(target);
        }
        if (outcome.consumedEnchantItem()) {
            cursorSetter.accept(decrease(rune));
        }
        EnchantDefinition definition = manager.definition(outcome.enchantId());
        String name = definition == null ? String.valueOf(outcome.enchantId())
                : RuneFormatting.coloredNameRaw(manager.tierOf(outcome.enchantId()), definition.displayName());
        player.sendMessage(messages.get(player, message(outcome.result()), "enchant", name,
                "level", RuneFormatting.roman(outcome.level())));
    }

    private static String message(EnchantManager.ApplyResult result) {
        return switch (result) {
            case SUCCESS -> "enchant.apply-success";
            case FAILURE -> "enchant.apply-failure";
            case REJECT_INCOMPATIBLE -> "enchant.apply-incompatible";
            case REJECT_EQUAL_LEVEL -> "enchant.apply-equal-level";
            case REJECT_HIGHER_EXISTS -> "enchant.apply-higher-exists";
            case REJECT_INVALID -> "enchant.apply-invalid";
        };
    }

    /**
     * A direct application is deliberate only when a Rune is dropped on real
     * gear/item content. Empty slots and other Rune-family items must retain
     * normal inventory behavior so identical identified Runes can stack and
     * players can move them around before applying one.
     */
    boolean isApplicationTarget(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        return !manager.isRune(item) && !manager.isEnchantItem(item) && !manager.isLuckyGem(item);
    }

    private static void consumeHand(Player player, EquipmentSlot hand, ItemStack item) {
        if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(decrease(item));
        } else {
            player.getInventory().setItemInMainHand(decrease(item));
        }
    }

    private static ItemStack decrease(ItemStack item) {
        return item == null || item.getAmount() <= 1 ? null : withAmount(item, item.getAmount() - 1);
    }

    private static ItemStack withAmount(ItemStack item, int amount) {
        ItemStack result = item.clone();
        result.setAmount(amount);
        return result;
    }

    private static boolean hasRoomFor(Player player, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return true;
        }
        int remaining = item.getAmount();
        for (ItemStack existing : player.getInventory().getStorageContents()) {
            if (existing == null || existing.getType().isAir()) {
                remaining -= item.getMaxStackSize();
            } else if (existing.isSimilar(item)) {
                remaining -= Math.max(0, item.getMaxStackSize() - existing.getAmount());
            }
            if (remaining <= 0) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        ItemStack result = event.getResult();
        if (result != null && !result.getType().isAir()) {
            manager.preserveAcrossTransform(event.getInventory().getItem(0), result);
            event.setResult(result);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        ItemStack result = event.getResult();
        if (result != null && !result.getType().isAir()) {
            manager.preserveAcrossTransform(event.getInventory().getInputEquipment(), result);
            event.setResult(result);
        }
    }
}
