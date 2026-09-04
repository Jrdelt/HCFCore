package me.hcfcore.core.ability;

import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.user.UserManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Re-skinned fishing rod. First right-click casts a real FishHook (so it
 * looks and behaves like a cast in flight); a second right-click while
 * that hook is out reels the player toward it and removes it. Doesn't rely
 * on PlayerFishEvent's own state machine -- simpler and more reliable for
 * an item that isn't doing real fishing.
 */
public final class GrapplingHookListener implements Listener {

    private static final String ABILITY_ID = "grappling-hook";

    private final Plugin plugin;
    private final AbilityManager abilityManager;
    private final UserManager userManager;
    private final Messages messages;
    private final Map<UUID, FishHook> activeHooks = new ConcurrentHashMap<>();

    public GrapplingHookListener(Plugin plugin, AbilityManager abilityManager, UserManager userManager,
                                  Messages messages) {
        this.plugin = plugin;
        this.abilityManager = abilityManager;
        this.userManager = userManager;
        this.messages = messages;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!AbilityGate.isAbility(plugin, event.getItem(), ABILITY_ID)) {
            return;
        }

        Ability ability = abilityManager.get(ABILITY_ID);
        if (ability == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();

        FishHook activeHook = activeHooks.get(player.getUniqueId());
        if (activeHook != null && activeHook.isDead()) {
            activeHooks.remove(player.getUniqueId());
            activeHook = null;
        }
        if (activeHook != null) {
            reelIn(player, activeHook, ability);
            return;
        }

        if (!AbilityGate.checkAndStart(plugin, abilityManager, userManager, messages, player, ability, false)) {
            return;
        }
        activeHooks.put(player.getUniqueId(), player.launchProjectile(FishHook.class));
    }

    private void reelIn(Player player, FishHook hook, Ability ability) {
        activeHooks.remove(player.getUniqueId());
        if (!hook.isDead()) {
            Location hookLocation = hook.getLocation();
            double forwardMultiplier = ability.getDouble("forward-multiplier", 1.6);
            double yMultiplier = ability.getDouble("y-multiplier", 0.8);

            Vector pull = hookLocation.toVector().subtract(player.getLocation().toVector());
            double length = Math.max(1.0, pull.length());
            pull.multiply(forwardMultiplier / length);
            pull.setY(pull.getY() + yMultiplier);
            player.setVelocity(pull);
            FallDamageImmunity.grant(player.getUniqueId(), 8);
            consumeUse(player, ability.getInt("uses", 8));
        }
        hook.remove();
    }

    private void consumeUse(Player player, int maximumUses) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType() == Material.AIR) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        NamespacedKey usesKey = new NamespacedKey(plugin, AbilityManager.USES_KEY);
        int uses = meta.getPersistentDataContainer().getOrDefault(
                usesKey, PersistentDataType.INTEGER, Math.max(1, maximumUses)) - 1;
        if (uses <= 0) {
            player.getInventory().setItemInMainHand(null);
            return;
        }
        meta.getPersistentDataContainer().set(usesKey, PersistentDataType.INTEGER, uses);
        item.setItemMeta(meta);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        FishHook hook = activeHooks.remove(event.getPlayer().getUniqueId());
        if (hook != null) {
            hook.remove();
        }
    }
}
