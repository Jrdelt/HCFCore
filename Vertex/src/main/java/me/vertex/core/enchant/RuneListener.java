package me.vertex.core.enchant;

import me.vertex.core.lang.Messages;
import org.bukkit.entity.Item;
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

/** Direct-inventory interaction path for legacy and Mob Arena Runes. */
public final class RuneListener implements Listener {
    private final EnchantManager manager;
    private final Messages messages;
    private volatile ArenaRuneManager arenaRunes;

    public RuneListener(EnchantManager manager, Messages messages) {
        this.manager = manager;
        this.messages = messages;
    }

    public void setArenaRunes(ArenaRuneManager arenaRunes) {
        this.arenaRunes = arenaRunes;
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
            identifyLegacy(player, event.getHand(), item, tier);
            return;
        }
        ArenaRuneManager arena = arenaRunes;
        if (arena != null && arena.isRune(item)) {
            event.setCancelled(true);
            identifyArena(player, event.getHand(), item, arena);
            return;
        }
        if (manager.isEnchantItem(item) || (arena != null && arena.isEnchant(item))) {
            event.setCancelled(true);
            player.sendMessage(messages.get(player, "rune.drag-apply"));
        }
    }

    /** PDC-marked Rune cosmetics may never become blocks; normal candles are untouched. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        ArenaRuneManager arena = arenaRunes;
        if (manager.isRune(item) || manager.isEnchantItem(item) || manager.isLuckyGem(item)
                || (arena != null && (arena.isRune(item) || arena.isEnchant(item) || arena.isLuckyGem(item)))) {
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
        ArenaRuneManager arena = arenaRunes;
        if (isLuckyGem(cursor, arena)) {
            if (manager.isEnchantItem(clicked)) {
                event.setCancelled(true);
                applyLegacyGem(player, clicked, cursor, event::setCurrentItem, event::setCursor, inventory);
                return;
            }
            if (arena != null && arena.isEnchant(clicked)) {
                event.setCancelled(true);
                applyArenaGem(player, clicked, cursor, event::setCurrentItem, event::setCursor, inventory, arena);
                return;
            }
        }
        if (!isApplicationTarget(clicked, arena)) {
            return;
        }
        if (manager.isEnchantItem(cursor)) {
            event.setCancelled(true);
            applyLegacyRune(player, clicked, cursor, event::setCurrentItem, event::setCursor);
        } else if (arena != null && arena.isEnchant(cursor)) {
            event.setCancelled(true);
            applyArenaRune(player, clicked, cursor, event::setCurrentItem, event::setCursor, arena);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlots().size() != 1) {
            return;
        }
        ArenaRuneManager arena = arenaRunes;
        ItemStack cursor = event.getOldCursor();
        int rawSlot = event.getRawSlots().iterator().next();
        if (!(event.getView().getInventory(rawSlot) instanceof PlayerInventory inventory)) {
            return;
        }
        int slot = event.getView().convertSlot(rawSlot);
        ItemStack clicked = inventory.getItem(slot);
        if (isLuckyGem(cursor, arena)) {
            if (manager.isEnchantItem(clicked)) {
                event.setCancelled(true);
                applyLegacyGem(player, clicked, cursor, upgraded -> inventory.setItem(slot, upgraded),
                        player::setItemOnCursor, inventory);
            } else if (arena != null && arena.isEnchant(clicked)) {
                event.setCancelled(true);
                applyArenaGem(player, clicked, cursor, upgraded -> inventory.setItem(slot, upgraded),
                        player::setItemOnCursor, inventory, arena);
            }
            return;
        }
        if (!isApplicationTarget(clicked, arena)) {
            return;
        }
        if (manager.isEnchantItem(cursor)) {
            event.setCancelled(true);
            applyLegacyRune(player, clicked, cursor, upgraded -> inventory.setItem(slot, upgraded),
                    player::setItemOnCursor);
        } else if (arena != null && arena.isEnchant(cursor)) {
            event.setCancelled(true);
            applyArenaRune(player, clicked, cursor, upgraded -> inventory.setItem(slot, upgraded),
                    player::setItemOnCursor, arena);
        }
    }

    private void identifyLegacy(Player player, EquipmentSlot hand, ItemStack baseRune, RuneTier tier) {
        EnchantManager.RollOutcome outcome = manager.rollRune(tier, Math.random());
        if (!outcome.ok()) {
            player.sendMessage(messages.get(player, "rune.table-empty"));
            return;
        }
        boolean hadRoomBeforeIdentifying = hasRoomFor(player, outcome.createdItem());
        consumeHand(player, hand, baseRune);
        admitIdentifiedRune(player, outcome.createdItem(), hadRoomBeforeIdentifying);
        EnchantDefinition definition = manager.definition(outcome.enchantId());
        player.sendMessage(messages.get(player, "rune.rolled", "enchant",
                definition == null ? outcome.enchantId() : definition.displayName(),
                "level", RuneFormatting.roman(outcome.level())));
    }

    private void identifyArena(Player player, EquipmentSlot hand, ItemStack baseRune, ArenaRuneManager arena) {
        ItemStack rolled = arena.rollRune();
        boolean hadRoomBeforeIdentifying = hasRoomFor(player, rolled);
        consumeHand(player, hand, baseRune);
        admitIdentifiedRune(player, rolled, hadRoomBeforeIdentifying);
        ArenaRuneManager.RuneInfo info = arena.info(rolled);
        player.sendMessage(messages.get(player, "arena-runes.revealed", "enchant",
                info == null ? "Arena Rune" : info.effect().displayName()));
    }

    private void admitIdentifiedRune(Player player, ItemStack rune, boolean hadRoomBeforeIdentifying) {
        if (hadRoomBeforeIdentifying) {
            player.getInventory().addItem(rune);
            return;
        }
        Item dropped = player.getWorld().dropItem(player.getLocation(), rune);
        dropped.setOwner(player.getUniqueId());
        player.sendMessage(messages.get(player, "rune.identify-inventory-full"));
    }

    private void applyLegacyGem(Player player, ItemStack clicked, ItemStack cursor,
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

    private void applyArenaGem(Player player, ItemStack clicked, ItemStack cursor,
            Consumer<ItemStack> targetSetter, Consumer<ItemStack> cursorSetter, PlayerInventory inventory,
            ArenaRuneManager arena) {
        ItemStack upgraded = arena.addLuckyGem(clicked);
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

    private void applyLegacyRune(Player player, ItemStack target, ItemStack rune,
            Consumer<ItemStack> targetSetter, Consumer<ItemStack> cursorSetter) {
        EnchantManager.ApplyOutcome outcome = manager.applyEnchant(target, rune, 0, Math.random());
        if (outcome.result() == EnchantManager.ApplyResult.SUCCESS) {
            targetSetter.accept(target);
        }
        if (outcome.consumedEnchantItem()) {
            cursorSetter.accept(decrease(rune));
        }
        EnchantDefinition definition = manager.definition(outcome.enchantId());
        String name = definition == null ? String.valueOf(outcome.enchantId()) : definition.displayName();
        player.sendMessage(messages.get(player, legacyMessage(outcome.result()), "enchant", name,
                "level", RuneFormatting.roman(outcome.level())));
    }

    private void applyArenaRune(Player player, ItemStack target, ItemStack rune,
            Consumer<ItemStack> targetSetter, Consumer<ItemStack> cursorSetter, ArenaRuneManager arena) {
        ArenaRuneManager.ApplyOutcome outcome = arena.apply(target, rune, 0);
        if (outcome.result() == ArenaRuneManager.ApplyResult.SUCCESS) {
            targetSetter.accept(target);
        }
        if (outcome.consumeEnchant()) {
            cursorSetter.accept(decrease(rune));
        }
        ArenaRuneManager.RuneInfo info = outcome.info();
        player.sendMessage(messages.get(player, arenaMessage(outcome.result()), "enchant",
                info == null ? "Arena Rune" : info.effect().displayName(),
                "level", info == null ? "" : RuneFormatting.roman(info.level())));
    }

    private static String legacyMessage(EnchantManager.ApplyResult result) {
        return switch (result) {
            case SUCCESS -> "enchant.apply-success";
            case FAILURE -> "enchant.apply-failure";
            case REJECT_INCOMPATIBLE -> "enchant.apply-incompatible";
            case REJECT_EQUAL_LEVEL -> "enchant.apply-equal-level";
            case REJECT_HIGHER_EXISTS -> "enchant.apply-higher-exists";
            case REJECT_INVALID -> "enchant.apply-invalid";
        };
    }

    private static String arenaMessage(ArenaRuneManager.ApplyResult result) {
        return switch (result) {
            case SUCCESS -> "arena-runes.applied";
            case FAILURE -> "arena-runes.failed";
            case INCOMPATIBLE -> "arena-runes.incompatible";
            case ALREADY_APPLIED -> "arena-runes.already-applied";
            case INVALID -> "arena-runes.invalid-application";
        };
    }

    private boolean isLuckyGem(ItemStack item, ArenaRuneManager arena) {
        return manager.isLuckyGem(item) || arena != null && arena.isLuckyGem(item);
    }

    /**
     * A direct application is deliberate only when a Rune is dropped on real
     * gear/item content. Empty slots and other Rune-family items must retain
     * normal inventory behavior so identical identified Runes can stack and
     * players can move them around before applying one.
     */
    boolean isApplicationTarget(ItemStack item, ArenaRuneManager arena) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        return !manager.isRune(item) && !manager.isEnchantItem(item) && !manager.isLuckyGem(item)
                && (arena == null || (!arena.isRune(item) && !arena.isEnchant(item) && !arena.isLuckyGem(item)));
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
