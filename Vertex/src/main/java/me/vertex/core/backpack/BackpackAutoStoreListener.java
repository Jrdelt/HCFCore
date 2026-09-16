package me.vertex.core.backpack;

import me.vertex.core.lang.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/** Routes configured mining drops and player-killed mob drops into an equipped Backpack. */
public final class BackpackAutoStoreListener implements Listener {
    private final BackpackManager manager;
    private final BackpackFilterManager filters;
    private final Messages messages;
    /** MineListener owns configured mine drops, including anti-silk-touch rules. */
    private volatile Predicate<org.bukkit.Location> mineRegion = location -> false;
    /**
     * Once a Backpack is full, {@link BackpackManager#storeWithResult} stops
     * silently (overflow just reroutes to the inventory/ground) -- with
     * nothing else, the player's only signal was the storage action bar
     * simply no longer updating, which reads exactly like a bug instead of
     * "you're full." This debounces a one-shot "full" notice per player so
     * mining right through a full Backpack doesn't spam it every block.
     */
    private static final long FULL_NOTICE_COOLDOWN_MILLIS = 8_000L;
    private final Map<UUID, Long> lastFullNoticeAt = new ConcurrentHashMap<>();

    public BackpackAutoStoreListener(BackpackManager manager, BackpackFilterManager filters, Messages messages) {
        this.manager = manager;
        this.filters = filters;
        this.messages = messages;
    }

    /** Prevent vanilla drop lookup from racing MineListener's custom-drop path. */
    public void setMineRegionPredicate(Predicate<org.bukkit.Location> mineRegion) {
        this.mineRegion = mineRegion == null ? location -> false : mineRegion;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMine(BlockBreakEvent event) {
        if (!event.isDropItems() || mineRegion.test(event.getBlock().getLocation())
                || !manager.autoStoresMining(event.getBlock().getType())) {
            return;
        }
        Player player = event.getPlayer();
        BackpackManager.EquippedBackpack equipped = manager.equippedBackpack(player);
        if (equipped == null) {
            return;
        }
        List<ItemStack> drops = new ArrayList<>(event.getBlock().getDrops(player.getInventory().getItemInMainHand(), player));
        event.setDropItems(false);
        List<ItemStack> overflow = route(player, equipped, drops);
        me.vertex.core.storage.ItemGiver.give(player, overflow);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastFullNoticeAt.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Player || !manager.autoStoresMobDrops()) {
            return;
        }
        Player player = event.getEntity().getKiller();
        if (player == null) {
            return;
        }
        BackpackManager.EquippedBackpack equipped = manager.equippedBackpack(player);
        if (equipped == null) {
            return;
        }
        List<ItemStack> drops = new ArrayList<>(event.getDrops());
        event.getDrops().clear();
        event.getDrops().addAll(route(player, equipped, drops));
    }

    private List<ItemStack> route(Player player, BackpackManager.EquippedBackpack equipped, List<ItemStack> drops) {
        List<ItemStack> accepted = new ArrayList<>();
        for (ItemStack drop : drops) {
            if (drop != null && !drop.isEmpty() && !filters.isFiltered(player.getUniqueId(), drop.getType())) {
                accepted.add(drop);
            }
        }
        long storedBefore = BackpackManager.storedItemCount(equipped.data().contents());
        BackpackManager.StoreResult result = manager.storeAutoCollectedWithResult(equipped, accepted);
        if (result.totalStored() > storedBefore) {
            // ItemMeta changes are normally visible through Bukkit's inventory
            // reference, but setting it explicitly also covers implementations
            // that hand us a defensive ItemStack copy. updateInventory() forces
            // the client to actually re-render the tooltip -- an in-place
            // ItemMeta/lore change doesn't reliably trigger a resend on its own.
            // Set a distinct stack so the newly rebuilt contents lore is always
            // sent to the client, even on implementations that cache the old
            // ItemStack instance. Only worth doing when something was actually
            // stored -- writeData only touched the item's meta in that case, so
            // this would otherwise be a wasted full-inventory resend packet on
            // every single qualifying block/mob-kill event, including every one
            // that hits a full or fully-filtered Backpack while mining.
            player.getInventory().setItemInOffHand(equipped.item().clone());
            player.updateInventory();
            sendStorageActionBar(player, equipped, result.totalStored());
        } else if (!accepted.isEmpty() && !result.leftovers().isEmpty()) {
            sendFullActionBarIfDue(player, equipped);
        }
        return result.leftovers();
    }

    private void sendFullActionBarIfDue(Player player, BackpackManager.EquippedBackpack equipped) {
        long now = System.currentTimeMillis();
        Long last = lastFullNoticeAt.get(player.getUniqueId());
        if (last != null && now - last < FULL_NOTICE_COOLDOWN_MILLIS) {
            return;
        }
        lastFullNoticeAt.put(player.getUniqueId(), now);
        player.sendActionBar(messages.get(player, "backpack.full-action-bar",
                "backpack_name", manager.displayName(equipped.tier()),
                "backpack_level", String.valueOf(equipped.data().level()),
                "used_storage", String.format("%,d", manager.itemCapacityForLevel(equipped.data().level())),
                "storage", String.format("%,d", manager.itemCapacityForLevel(equipped.data().level()))));
    }

    /** Shows an accurate total only when this collection event added an item. */
    private void sendStorageActionBar(Player player, BackpackManager.EquippedBackpack equipped, long totalStored) {
        player.sendActionBar(messages.get(player, "backpack.store-action-bar",
                "backpack_name", manager.displayName(equipped.tier()),
                "tier", manager.tierLabel(equipped.tier()),
                "backpack_level", String.valueOf(equipped.data().level()),
                "used_storage", String.format("%,d", totalStored),
                "storage", String.format("%,d", manager.itemCapacityForLevel(equipped.data().level()))));
    }

    /** Handles drops produced directly by Vertex's mob-stack peeling path. */
    public List<ItemStack> routePlayerMobDrops(Player player, List<ItemStack> drops) {
        if (!manager.autoStoresMobDrops()) {
            return drops;
        }
        BackpackManager.EquippedBackpack equipped = manager.equippedBackpack(player);
        return equipped == null ? drops : route(player, equipped, drops);
    }

    /**
     * Routes a drop that another Vertex subsystem has already calculated.
     * The caller retains responsibility for its leftovers. In particular,
     * MineListener supplies its configured ingot/drop result here instead of
     * asking Bukkit for a second, Silk-Touch-sensitive vanilla drop list.
     */
    public List<ItemStack> routePlayerMiningDrops(Player player, List<ItemStack> drops) {
        BackpackManager.EquippedBackpack equipped = manager.equippedBackpack(player);
        return equipped == null ? drops : route(player, equipped, drops);
    }
}
