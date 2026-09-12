package me.vertex.core.enchant;

import me.vertex.core.booster.BoosterCategory;
import me.vertex.core.booster.BoosterContribution;
import me.vertex.core.booster.BoosterSource;
import me.vertex.core.lang.Messages;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Handles rolling, applying, purchasing, and live Arena Rune effects. */
public final class ArenaRuneListener implements Listener {
    private final Plugin plugin;
    private final ArenaRuneManager manager;
    private final Messages messages;

    public ArenaRuneListener(Plugin plugin, ArenaRuneManager manager, Messages messages) {
        this.plugin = plugin;
        this.manager = manager;
        this.messages = messages;
    }

    public BoosterSource boosterSource() {
        return new BoosterSource() {
            @Override public String id() { return "arena-runes"; }
            @Override public List<BoosterContribution> contribute(Player player, BoosterCategory category) {
                if (category != BoosterCategory.MOB_DROP) return List.of();
                if (!manager.inArena(player)) return List.of();
                return List.of(BoosterContribution.active(id(), category,
                        manager.equippedValue(player, ArenaRuneManager.Effect.ABYSSAL_SCAVENGER)));
            }
        };
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        Player player = event.getPlayer();
        if (manager.isRune(item)) {
            event.setCancelled(true);
            ItemStack rolled = manager.rollRune();
            if (!give(player, rolled)) return;
            consumeMainHand(player, item);
            player.sendMessage(messages.get(player, "arena-runes.revealed", "enchant", manager.info(rolled).effect().displayName()));
        } else if (manager.isEnchant(item)) {
            event.setCancelled(true);
            player.sendMessage(messages.get(player, "arena-runes.drag-apply"));
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() instanceof PlayerInventory && handleDirectClick(event, player)) return;
    }
    private boolean handleDirectClick(InventoryClickEvent event, Player player) {
        ItemStack cursor = event.getCursor();
        ItemStack clicked = event.getCurrentItem();
        if (manager.isLuckyGem(cursor) && manager.isEnchant(clicked)) {
            ItemStack upgraded = manager.addLuckyGem(clicked);
            event.setCancelled(true);
            if (upgraded == null) {
                player.sendMessage(messages.get(player, "arena-runes.success-capped"));
                return true;
            }
            if (clicked.getAmount() > 1) {
                event.setCurrentItem(decrease(clicked));
                give(player, upgraded);
            } else event.setCurrentItem(upgraded);
            event.setCursor(decrease(cursor));
            player.sendMessage(messages.get(player, "arena-runes.lucky-gem-added"));
            return true;
        }
        if (!manager.isEnchant(cursor) || clicked == null || clicked.getType().isAir()) return false;
        event.setCancelled(true);
        ArenaRuneManager.ApplyOutcome outcome = manager.apply(clicked, cursor, 0);
        if (outcome.result() == ArenaRuneManager.ApplyResult.SUCCESS) {
            event.setCurrentItem(clicked);
            player.sendMessage(messages.get(player, "arena-runes.applied", "enchant", outcome.info().effect().displayName(), "level", String.valueOf(outcome.info().level())));
        } else if (outcome.result() == ArenaRuneManager.ApplyResult.FAILURE) {
            player.sendMessage(messages.get(player, "arena-runes.failed"));
        } else if (outcome.result() == ArenaRuneManager.ApplyResult.INCOMPATIBLE) {
            player.sendMessage(messages.get(player, "arena-runes.incompatible"));
        } else if (outcome.result() == ArenaRuneManager.ApplyResult.ALREADY_APPLIED) {
            player.sendMessage(messages.get(player, "arena-runes.already-applied"));
        } else player.sendMessage(messages.get(player, "arena-runes.invalid-application"));
        if (outcome.consumeEnchant()) event.setCursor(decrease(cursor));
        return true;
    }
    private static ItemStack decrease(ItemStack item) { if (item == null || item.getAmount() <= 1) return null; ItemStack copy = item.clone(); copy.setAmount(copy.getAmount() - 1); return copy; }
    private static void consumeMainHand(Player player, ItemStack item) { player.getInventory().setItemInMainHand(decrease(item)); }
    private static boolean give(Player player, ItemStack item) { if (item == null || item.getType().isAir()) return true; var overflow = player.getInventory().addItem(item); overflow.values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left)); return true; }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (event.getDamager() instanceof Player attacker) {
            if (!manager.inArena(attacker) || !manager.inArena(victim)) return;
            double strike = manager.equippedValue(attacker, ArenaRuneManager.Effect.ECLIPSE_STRIKE);
            double ward = manager.equippedValue(victim, ArenaRuneManager.Effect.ECLIPSE_WARD);
            event.setDamage(event.getDamage() * (1D + strike / 100D) * Math.max(0D, 1D - ward / 100D));
            return;
        }
        if (manager.inArena(victim) && manager.inArena(event.getDamager().getLocation())) {
            double shield = manager.equippedValue(victim, ArenaRuneManager.Effect.VOID_SHIELD);
            event.setDamage(event.getDamage() * Math.max(0D, 1D - shield / 100D));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        Player player = event.getEntity().getKiller();
        if (player == null || !manager.inArena(player) || !manager.inArena(event.getEntity().getLocation())) return;
        double insight = manager.equippedValue(player, ArenaRuneManager.Effect.ABYSSAL_INSIGHT);
        if (insight > 0D) event.setDroppedExp(event.getDroppedExp() + (int) Math.floor(event.getDroppedExp() * insight / 100D));
        double feast = manager.equippedValue(player, ArenaRuneManager.Effect.ABYSSAL_FEAST);
        if (feast > 0D) player.setHealth(Math.min(player.getMaxHealth(), player.getHealth() + player.getMaxHealth() * feast / 100D));
        double detonation = manager.detonationPercent(player);
        if (detonation <= 0D || ThreadLocalRandom.current().nextDouble(100D) >= 30D) return;
        double damage = event.getEntity().getMaxHealth() * detonation / 100D;
        for (Entity entity : event.getEntity().getNearbyEntities(manager.detonationRadius(), manager.detonationRadius(), manager.detonationRadius())) {
            if (!(entity instanceof LivingEntity target) || target instanceof Player || !manager.inArena(target.getLocation())) continue;
            target.damage(damage, player);
        }
    }
}
