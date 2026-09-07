package me.vertex.core.pvp;

import me.vertex.core.lang.Messages;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Makes PvP death drops temporarily collectible by the killer alone. */
public final class LootProtectionListener implements Listener {
    private final Messages messages;
    private final NamespacedKey ownerKey;
    private final NamespacedKey expiresKey;
    private final Map<UUID, Long> denialNotices = new ConcurrentHashMap<>();
    private volatile boolean enabled;
    private volatile long durationMillis;

    public LootProtectionListener(Plugin plugin, Messages messages) {
        this.messages = messages;
        this.ownerKey = new NamespacedKey(plugin, "loot_owner");
        this.expiresKey = new NamespacedKey(plugin, "loot_protected_until");
        reload(plugin);
    }

    public void reload(Plugin plugin) {
        enabled = plugin.getConfig().getBoolean("pvp.loot-protection.enabled", true);
        durationMillis = Math.max(0L, plugin.getConfig().getLong("pvp.loot-protection.seconds", 20L)) * 1000L;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (!enabled || durationMillis <= 0L || killer == null || event.getDrops().isEmpty()) {
            return;
        }
        long expiresAt = System.currentTimeMillis() + durationMillis;
        for (ItemStack drop : event.getDrops()) {
            if (drop == null || drop.isEmpty()) {
                continue;
            }
            ItemMeta meta = drop.getItemMeta();
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(ownerKey, PersistentDataType.STRING, killer.getUniqueId().toString());
            pdc.set(expiresKey, PersistentDataType.LONG, expiresAt);
            drop.setItemMeta(meta);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        ItemStack stack = event.getItem().getItemStack();
        if (!isProtected(stack, player)) {
            return;
        }
        event.setCancelled(true);
        long now = System.currentTimeMillis();
        if (denialNotices.getOrDefault(player.getUniqueId(), 0L) <= now) {
            denialNotices.put(player.getUniqueId(), now + 1000L);
            player.sendActionBar(messages.get(player, "combat.loot-protected"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMerge(ItemMergeEvent event) {
        // A protected and an unprotected stack must never merge: otherwise
        // Bukkit would retain only one PDC payload and leak/drop protection.
        if (hasLiveProtection(event.getEntity()) || hasLiveProtection(event.getTarget())) {
            event.setCancelled(true);
        }
    }

    private boolean isProtected(ItemStack stack, Player picker) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Long expiresAt = pdc.get(expiresKey, PersistentDataType.LONG);
        String owner = pdc.get(ownerKey, PersistentDataType.STRING);
        if (expiresAt == null || owner == null) {
            clear(pdc, stack, meta);
            return false;
        }
        if (expiresAt <= System.currentTimeMillis()) {
            clear(pdc, stack, meta);
            return false;
        }
        if (picker.getUniqueId().toString().equals(owner)) {
            // Once the entitled killer collects a stack it is ordinary
            // inventory again; retaining protection would incorrectly lock
            // it if they later drop or trade it before the timer ends.
            clear(pdc, stack, meta);
            return false;
        }
        return true;
    }

    private boolean hasLiveProtection(Item item) {
        ItemStack stack = item.getItemStack();
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Long expiresAt = pdc.get(expiresKey, PersistentDataType.LONG);
        if (expiresAt != null && expiresAt > System.currentTimeMillis()) {
            return true;
        }
        if (expiresAt != null) {
            clear(pdc, stack, meta);
            item.setItemStack(stack);
        }
        return false;
    }

    private void clear(PersistentDataContainer pdc, ItemStack stack, ItemMeta meta) {
        pdc.remove(ownerKey);
        pdc.remove(expiresKey);
        stack.setItemMeta(meta);
    }
}
