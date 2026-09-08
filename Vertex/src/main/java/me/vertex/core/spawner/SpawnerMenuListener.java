package me.vertex.core.spawner;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.factions.FactionsHook;
import me.vertex.core.faction.RallyManager;
import me.vertex.core.lang.Messages;
import me.vertex.core.shop.ShopManager;
import me.vertex.core.shop.ShopMenu;
import me.vertex.core.staff.StaffManager;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** Routes clicks for both SpawnerShopMenu (buy) and SpawnerManagementMenu (withdraw/sell). */
public final class SpawnerMenuListener implements Listener {

    private final SpawnerManager spawnerManager;
    private final StaffManager staffManager;
    private final Messages messages;
    private final RallyManager rolePermissions;
    private volatile ShopManager shopManager;

    public SpawnerMenuListener(SpawnerManager spawnerManager, StaffManager staffManager, Messages messages, RallyManager rolePermissions) {
        this.spawnerManager = spawnerManager;
        this.staffManager = staffManager;
        this.messages = messages;
        this.rolePermissions = rolePermissions;
    }

    /**
     * Wired in after both managers exist -- {@code /spawners} no longer
     * exists as its own command, so the shop's category picker (this
     * menu's "back" button) is only reachable through here.
     */
    public void setShopManager(ShopManager shopManager) {
        this.shopManager = shopManager;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof SpawnerShopMenu.Holder
                || event.getInventory().getHolder() instanceof SpawnerManagementMenu.Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof SpawnerShopMenu.Holder shop) {
            onShopClick(event, shop);
        } else if (event.getInventory().getHolder() instanceof SpawnerManagementMenu.Holder management) {
            onManagementClick(event, management);
        }
    }

    private void onShopClick(InventoryClickEvent event, SpawnerShopMenu.Holder holder) {
        event.setCancelled(true);
        if (event.getClickedInventory() == null || !(event.getClickedInventory().getHolder() instanceof SpawnerShopMenu.Holder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getSlot() == SpawnerShopMenu.SLOT_BACK) {
            ShopManager currentShopManager = shopManager;
            if (currentShopManager != null) {
                ShopMenu.openCategories(player, currentShopManager, spawnerManager, messages);
            }
            return;
        }
        EntityType mobType = holder.mobTypeAt(event.getSlot());
        if (mobType == null) {
            return;
        }
        SpawnerManager.MobConfig config = spawnerManager.getMobConfig(mobType);
        if (config == null) {
            return;
        }
        if (!EconomyHook.isAvailable()) {
            player.sendMessage(messages.get(player, "spawner.no-economy"));
            return;
        }
        Economy economy = EconomyHook.getEconomy();
        EconomyResponse response = economy.withdrawPlayer(player, config.price());
        if (!response.transactionSuccess()) {
            player.sendMessage(messages.get(player, "spawner.cannot-afford", "amount", EconomyHook.format(config.price())));
            return;
        }
        ItemStack item = SpawnerManager.createSpawnerItem(mobType,
                me.vertex.core.lang.MessageFormatter.deserialize(config.displayName()));
        for (ItemStack dropped : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), dropped);
        }
        player.sendMessage(messages.get(player, "spawner.purchased", "amount", EconomyHook.format(config.price())));
    }

    private void onManagementClick(InventoryClickEvent event, SpawnerManagementMenu.Holder holder) {
        event.setCancelled(true);
        if (event.getClickedInventory() == null
                || !(event.getClickedInventory().getHolder() instanceof SpawnerManagementMenu.Holder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Location location = holder.location();
        SpawnerData data = spawnerManager.get(location);
        if (data == null) {
            player.closeInventory();
            return;
        }
        // Defense-in-depth: re-checked here too (not just before the menu
        // opens in SpawnerListener.onInteract), in case the land changed
        // hands or the player left the faction while the menu was already
        // open -- withdraw/sell must never work against someone else's claim.
        if (!staffManager.isStaffBuild(player.getUniqueId())) {
            String claimTag = FactionsHook.getClaimFactionTag(location);
            String playerTag = FactionsHook.getFactionTag(player);
            if (claimTag == null || !claimTag.equalsIgnoreCase(playerTag)) {
                player.sendMessage(messages.get(player, "spawner.not-your-claim"));
                player.closeInventory();
                return;
            }
        }
        int slot = event.getSlot();
        if (!staffManager.isStaffBuild(player.getUniqueId()) && !rolePermissions.canUse(player, "spawner-remove")) {
            player.sendMessage(messages.get(player, "factions.role-permission-denied"));
            player.closeInventory();
            return;
        }
        if (slot == SpawnerManagementMenu.WITHDRAW_ONE_SLOT) {
            withdraw(player, location, data, 1);
        } else if (slot == SpawnerManagementMenu.WITHDRAW_ALL_SLOT) {
            withdraw(player, location, data, data.stackSize());
        } else if (slot == SpawnerManagementMenu.SELL_ONE_SLOT) {
            sell(player, location, data, 1);
        } else if (slot == SpawnerManagementMenu.SELL_ALL_SLOT) {
            sell(player, location, data, data.stackSize());
        } else {
            return;
        }
        player.closeInventory();
    }

    private void withdraw(Player player, Location location, SpawnerData data, int amount) {
        amount = Math.min(amount, data.stackSize());
        if (amount <= 0) {
            return;
        }
        SpawnerManager.MobConfig config = spawnerManager.getMobConfig(data.mobType());
        net.kyori.adventure.text.Component displayName = config != null
                ? me.vertex.core.lang.MessageFormatter.deserialize(config.displayName())
                : net.kyori.adventure.text.Component.text(data.mobType().name());

        PlayerInventory inventory = player.getInventory();
        for (int i = 0; i < amount; i++) {
            for (ItemStack dropped : inventory.addItem(SpawnerManager.createSpawnerItem(data.mobType(), displayName)).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), dropped);
            }
        }
        int newSize = spawnerManager.decreaseStack(location, amount);
        clearBlockIfEmpty(location, newSize);
        player.sendMessage(messages.get(player, "spawner.withdrew", "amount", String.valueOf(amount)));
    }

    private void sell(Player player, Location location, SpawnerData data, int amount) {
        amount = Math.min(amount, data.stackSize());
        if (amount <= 0) {
            return;
        }
        SpawnerManager.MobConfig config = spawnerManager.getMobConfig(data.mobType());
        double price = config != null ? config.price() : 0;
        double refund = price * spawnerManager.sellRefundPercent() / 100.0 * amount;

        if (refund > 0) {
            if (!EconomyHook.isAvailable()) {
                player.sendMessage(messages.get(player, "spawner.no-economy"));
                return;
            }
            EconomyResponse response = EconomyHook.getEconomy().depositPlayer(player, refund);
            if (!response.transactionSuccess()) {
                player.sendMessage(messages.get(player, "spawner.no-economy"));
                return;
            }
        }
        int newSize = spawnerManager.decreaseStack(location, amount);
        clearBlockIfEmpty(location, newSize);
        player.sendMessage(messages.get(player, "spawner.sold", "amount", String.valueOf(amount),
                "refund", EconomyHook.format(refund)));
    }

    /**
     * Withdrawing/selling the last spawner in a stack untracks it (see
     * SpawnerManager#decreaseStack), but leaves the physical block behind
     * unless this clears it too -- otherwise it lingers as an inert,
     * un-interactable vanilla spawner block that can't be reopened, added
     * to, or broken normally, and won't drop later on an overclaim/disband
     * since it's no longer tracked.
     */
    private static void clearBlockIfEmpty(Location location, int newSize) {
        if (newSize <= 0 && location.getBlock().getType() == Material.SPAWNER) {
            location.getBlock().setType(Material.AIR);
        }
    }
}
