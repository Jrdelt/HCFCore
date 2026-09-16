package me.vertex.core.enchant.binds;

import me.vertex.core.enchant.EnchantManager;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.InventoryView;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Detects the player's chosen {@link BindActivationOpener}, intercepts
 * hotbar-number presses while the HUD is visible, and clears the ability
 * queue + HUD on every hard-stop context change. Guards every opener
 * against colliding with {@code RuneListener}'s own right-click identify/
 * apply handling by never treating a click as an opener while holding a
 * rune or enchant item.
 *
 * <p>Chat, anvil rename, and command-block editing need no special
 * handling here: a vanilla client suppresses swap-hand/attack/use-item
 * control packets entirely while any text-entry screen has keyboard
 * focus, so {@link PlayerSwapHandItemsEvent}/{@link PlayerInteractEvent}
 * simply never fire during those states. Right-clicking a sign or a book
 * is the one case that both opens a text screen <em>and</em> goes through
 * this class's own listened events (mouse-click openers), so those two
 * are excluded explicitly in {@link #opensTextEntryScreen}.
 */
public final class BindActivationListener implements Listener {

    private final EnchantManager enchants;
    private final BindManager binds;
    private final BindHudService hud;
    private final BindQueue queue;
    private final Map<UUID, Long> lastSwapPress = new HashMap<>();
    private final Map<UUID, Long> lastLeftClickAt = new HashMap<>();
    private final Map<UUID, Long> lastRightClickAt = new HashMap<>();
    /** Consecutive-fast-slot-change tracking for the scroll-spam guard in {@link #onItemHeld}. */
    private final Map<UUID, Long> lastHeldChangeAt = new HashMap<>();
    private final Map<UUID, Integer> rapidHeldChangeStreak = new HashMap<>();
    /** Two hotbar changes this close together reads as scrolling through slots, not a deliberate bind press. */
    private static final long RAPID_SCROLL_WINDOW_MILLIS = 300L;
    private static final int RAPID_SCROLL_ABORT_THRESHOLD = 2;

    public BindActivationListener(EnchantManager enchants, BindManager binds, BindHudService hud, BindQueue queue) {
        this.enchants = enchants;
        this.binds = binds;
        this.hud = hud;
        this.queue = queue;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (inputBlocked(player)) {
            return;
        }
        BindActivationOpener opener = binds.activationOpener(player);
        UUID uuid = player.getUniqueId();
        if (hud.isActive(uuid) && isClickBasedOpener(opener) && !player.isSneaking()) {
            // Bind 1's alternate trigger for a click-based opener (see the
            // matching guard in onInteract) -- a plain F press can't collide
            // with either LEFT_THEN_RIGHT_CLICK or SHIFT_RIGHT_CLICK the way
            // right-click itself would.
            event.setCancelled(true);
            hud.selectBind(player, 1);
            return;
        }
        if (opener == BindActivationOpener.SHIFT_F) {
            if (player.isSneaking()) {
                event.setCancelled(true);
                hud.activate(player);
            }
            return;
        }
        if (opener == BindActivationOpener.DOUBLE_TAP_F) {
            long now = System.currentTimeMillis();
            Long last = lastSwapPress.put(uuid, now);
            if (last != null && now - last <= binds.doubleTapWindowMillis(player) && !player.isSneaking()) {
                event.setCancelled(true);
                lastSwapPress.remove(uuid);
                hud.activate(player);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (inputBlocked(player) || isHoldingRuneItem(event.getItem()) || opensTextEntryScreen(event)) {
            return;
        }
        Action action = event.getAction();
        UUID uuid = player.getUniqueId();
        BindActivationOpener opener = binds.activationOpener(player);
        // Bind 1's hotbar number can never register while the HUD is open --
        // the HUD parks the player's held slot on slot 1 itself (see
        // BindHudService#PARKED_HOTBAR_SLOT) so every OTHER bind number is a
        // guaranteed genuine slot change, but that same parking means slot 1
        // is already selected, so pressing "1" produces no event at all. A
        // plain (non-sneaking) right-click is bind 1's alternate trigger --
        // except for a click-based opener (LEFT_THEN_RIGHT_CLICK,
        // SHIFT_RIGHT_CLICK), where right-click is itself part of the
        // opener gesture: a player closing/reopening the HUD, or just
        // right-clicking normally afterward, would otherwise fire bind 1 by
        // accident. Those two openers get a plain F press instead (see
        // onSwapHandItems). Sneaking is left alone either way so a
        // sneak+right-click opener gesture (below) still works exactly as
        // configured.
        if (hud.isActive(uuid) && !isClickBasedOpener(opener) && action.isRightClick() && !player.isSneaking()) {
            event.setCancelled(true);
            hud.selectBind(player, 1);
            return;
        }
        long now = System.currentTimeMillis();
        if (opener == BindActivationOpener.SHIFT_RIGHT_CLICK) {
            if (player.isSneaking() && event.getAction().isRightClick()) {
                event.setCancelled(true);
                hud.activate(player);
            }
            return;
        }
        if (opener != BindActivationOpener.LEFT_THEN_RIGHT_CLICK) {
            return;
        }
        long window = binds.doubleTapWindowMillis(player);
        if (action.isRightClick()) {
            Long leftAt = lastLeftClickAt.remove(uuid);
            if (leftAt != null && now - leftAt <= window) {
                event.setCancelled(true);
                hud.activate(player);
                return;
            }
            lastRightClickAt.put(uuid, now);
        } else if (action.isLeftClick()) {
            Long rightAt = lastRightClickAt.remove(uuid);
            if (rightAt != null && now - rightAt <= window) {
                event.setCancelled(true);
                hud.activate(player);
                return;
            }
            lastLeftClickAt.put(uuid, now);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!hud.isActive(uuid)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastHeldChangeAt.put(uuid, now);
        int streak = last != null && now - last <= RAPID_SCROLL_WINDOW_MILLIS
                ? rapidHeldChangeStreak.getOrDefault(uuid, 1) + 1 : 1;
        rapidHeldChangeStreak.put(uuid, streak);
        if (streak >= RAPID_SCROLL_ABORT_THRESHOLD) {
            // The player is scrolling through their hotbar, not deliberately
            // pressing one bind number -- let this and further scrolling
            // through unaffected, rather than fighting their own input by
            // restoring the slot the HUD parked them on.
            hud.hide(uuid, false);
            lastHeldChangeAt.remove(uuid);
            rapidHeldChangeStreak.remove(uuid);
            return;
        }
        event.setCancelled(true);
        hud.selectBind(player, event.getNewSlot() + 1);
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            InventoryView view = event.getView();
            // Opening the Bind menus themselves must not self-cancel their own queue/HUD.
            if (isBindMenu(view)) {
                return;
            }
            hardStop(player.getUniqueId());
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        hardStop(event.getEntity().getUniqueId());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        hardStop(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        hardStop(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        hardStop(uuid);
        lastSwapPress.remove(uuid);
        lastLeftClickAt.remove(uuid);
        lastRightClickAt.remove(uuid);
        lastHeldChangeAt.remove(uuid);
        rapidHeldChangeStreak.remove(uuid);
    }

    private void hardStop(UUID uuid) {
        queue.clear(uuid);
        hud.hide(uuid);
    }

    /** Openers where right-click is itself part of the gesture, so bind 1's alternate trigger has to be F instead -- see {@link BindHudService#bindOneAlternateTriggerLabel}. */
    static boolean isClickBasedOpener(BindActivationOpener opener) {
        return opener == BindActivationOpener.LEFT_THEN_RIGHT_CLICK || opener == BindActivationOpener.SHIFT_RIGHT_CLICK;
    }

    private boolean inputBlocked(Player player) {
        return player.getOpenInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING;
    }

    private boolean isHoldingRuneItem(ItemStack item) {
        return item != null && (enchants.isRune(item) || enchants.isEnchantItem(item) || enchants.isLuckyGem(item));
    }

    /** Right-clicking a sign or a book opens a client-side text screen instead of performing an opener gesture. */
    private boolean opensTextEntryScreen(PlayerInteractEvent event) {
        if (!event.getAction().isRightClick()) {
            return false;
        }
        ItemStack item = event.getItem();
        if (item != null && (item.getType() == Material.WRITABLE_BOOK || item.getType() == Material.WRITTEN_BOOK)) {
            return true;
        }
        Block clicked = event.getClickedBlock();
        return clicked != null && clicked.getBlockData() instanceof org.bukkit.block.data.type.Sign;
    }

    private static boolean isBindMenu(InventoryView view) {
        Object holder = view.getTopInventory().getHolder();
        return holder instanceof me.vertex.core.enchant.binds.menu.BindsHomeMenu.Holder
                || holder instanceof me.vertex.core.enchant.binds.menu.BindEditMenu.Holder
                || holder instanceof me.vertex.core.enchant.binds.menu.RuneSelectorMenu.Holder
                || holder instanceof me.vertex.core.enchant.binds.menu.PresetMenu.Holder
                || holder instanceof me.vertex.core.enchant.binds.menu.PresetConfirmMenu.Holder;
    }
}
