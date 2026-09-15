package me.vertex.core.resetvault;

import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Event listener handling all inventory interactions for Reset Vault GUIs.
 * Enforces anti-dupe, double-click prevention, and concurrency safety.
 */
public final class ResetVaultMenuListener implements Listener {

    private final ResetVaultManager manager;
    private final Messages messages;

    public ResetVaultMenuListener(ResetVaultManager manager, Messages messages) {
        this.manager = manager;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        InventoryHolder topHolder = event.getView().getTopInventory().getHolder();
        if (topHolder == null) {
            return;
        }

        // 1. ResetVaultMenu
        if (topHolder instanceof ResetVaultMenu.Holder holder) {
            handleResetVaultMenuClick(event, player, holder);
            return;
        }

        // 2. EligibleItemsMenu
        if (topHolder instanceof EligibleItemsMenu.Holder holder) {
            handleEligibleItemsMenuClick(event, player, holder);
            return;
        }

        // 3. DepositConfirmMenu
        if (topHolder instanceof DepositConfirmMenu.Holder holder) {
            handleDepositConfirmMenuClick(event, player, holder);
            return;
        }

        // 4. BackupConfirmMenu
        if (topHolder instanceof BackupConfirmMenu.Holder holder) {
            handleBackupConfirmMenuClick(event, player, holder);
            return;
        }

        // 5. RestoreBrowserMenu
        if (topHolder instanceof RestoreBrowserMenu.Holder holder) {
            handleRestoreBrowserMenuClick(event, player, holder);
            return;
        }

        // 6. RestoreConfirmMenu
        if (topHolder instanceof RestoreConfirmMenu.Holder holder) {
            handleRestoreConfirmMenuClick(event, player, holder);
            return;
        }

        // 7. BlacklistEditorMenu
        if (topHolder instanceof BlacklistEditorMenu.Holder holder) {
            handleBlacklistEditorMenuClick(event, player, holder);
            return;
        }

        // 8. AdminItemSelectorMenu
        if (topHolder instanceof AdminItemSelectorMenu.Holder holder) {
            handleAdminItemSelectorMenuClick(event, player, holder);
            return;
        }

        // 9. AdminVaultMenu
        if (topHolder instanceof AdminVaultMenu.Holder holder) {
            handleAdminVaultMenuClick(event, player, holder);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        InventoryHolder topHolder = event.getView().getTopInventory().getHolder();
        if (topHolder == null) return;

        if (topHolder instanceof ResetVaultMenu.Holder
                || topHolder instanceof EligibleItemsMenu.Holder
                || topHolder instanceof DepositConfirmMenu.Holder
                || topHolder instanceof BackupConfirmMenu.Holder
                || topHolder instanceof RestoreBrowserMenu.Holder
                || topHolder instanceof RestoreConfirmMenu.Holder
                || topHolder instanceof BlacklistEditorMenu.Holder
                || topHolder instanceof AdminItemSelectorMenu.Holder
                || topHolder instanceof AdminVaultMenu.Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        InventoryHolder topHolder = event.getInventory().getHolder();
        if (topHolder instanceof ResetVaultMenu.Holder
                || topHolder instanceof EligibleItemsMenu.Holder
                || topHolder instanceof DepositConfirmMenu.Holder) {
            Bukkit.getScheduler().runTaskLater(manager.plugin(), () -> {
                if (!player.isOnline()) {
                    manager.closeSession(player.getUniqueId());
                    return;
                }
                InventoryHolder current = player.getOpenInventory().getTopInventory().getHolder();
                if (!(current instanceof ResetVaultMenu.Holder)
                        && !(current instanceof EligibleItemsMenu.Holder)
                        && !(current instanceof DepositConfirmMenu.Holder)) {
                    manager.closeSession(player.getUniqueId());
                }
            }, 1L);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        manager.closeSession(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------
    // ResetVaultMenu Handlers
    // ------------------------------------------------------------------

    private void handleResetVaultMenuClick(InventoryClickEvent event, Player player, ResetVaultMenu.Holder holder) {
        event.setCancelled(true);

        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            return;
        }

        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        int slot = event.getSlot();
        ResetVaultSessionCheck(player, holder);

        if (slot == 45) { // Prev page
            if (holder.page() > 0) {
                holder.setPage(holder.page() - 1);
                ResetVaultMenu.render(player, holder, holder.getInventory());
            }
            return;
        }

        if (slot == 53) { // Next page
            holder.setPage(holder.page() + 1);
            ResetVaultMenu.render(player, holder, holder.getInventory());
            return;
        }

        if (slot >= 0 && slot < 45) {
            int logicalIndex = holder.page() * 45 + slot;
            ResetVaultData data = manager.getVaultData(player.getUniqueId());

            if (logicalIndex < data.itemCount()) {
                // Clicking an existing item
                if (manager.phase() == ResetVaultPhase.WITHDRAW) {
                    manager.withdrawItem(player, logicalIndex, () -> {
                        ResetVaultMenu.render(player, holder, holder.getInventory());
                    }, errorMsg -> {
                        player.sendMessage(errorMsg);
                    });
                }
            } else if (logicalIndex == data.itemCount()) {
                // Possible Nether Star deposit slot
                if (manager.phase() == ResetVaultPhase.DEPOSIT && data.itemCount() < manager.totalCapacity(player)) {
                    EligibleItemsMenu.open(player, manager, messages);
                }
            }
        }
    }

    private void ResetVaultSessionCheck(Player player, ResetVaultMenu.Holder holder) {
        var session = manager.getSession(player.getUniqueId());
        if (session != null && session.isReadOnly()) {
            player.sendMessage(messages.get(player, "reset-vault.session-read-only"));
        }
    }

    // ------------------------------------------------------------------
    // EligibleItemsMenu Handlers
    // ------------------------------------------------------------------

    private void handleEligibleItemsMenuClick(InventoryClickEvent event, Player player, EligibleItemsMenu.Holder holder) {
        event.setCancelled(true);

        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        int slot = event.getSlot();
        if (slot == EligibleItemsMenu.BACK_SLOT) {
            ResetVaultMenu.open(player, manager, messages, 0, -1);
            return;
        }

        if (slot >= 0 && slot < 36) {
            ItemStack clickedItem = player.getInventory().getItem(slot);
            if (clickedItem == null || clickedItem.getType().isAir()) {
                return;
            }

            ResetVaultManager.CheckResult check = manager.checkEligibility(clickedItem);
            if (check.result() == ResetVaultManager.EligibilityResult.ELIGIBLE) {
                ItemStack processed = manager.processItemForDeposit(clickedItem);
                DepositConfirmMenu.open(player, manager, messages, slot, clickedItem, processed);
            } else {
                player.sendMessage(messages.get(player, check.rejectionKey()));
            }
        }
    }

    // ------------------------------------------------------------------
    // DepositConfirmMenu Handlers
    // ------------------------------------------------------------------

    private void handleDepositConfirmMenuClick(InventoryClickEvent event, Player player, DepositConfirmMenu.Holder holder) {
        event.setCancelled(true);

        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        int slot = event.getSlot();
        if (slot == DepositConfirmMenu.CANCEL_SLOT) {
            EligibleItemsMenu.open(player, manager, messages);
            return;
        }

        if (slot == DepositConfirmMenu.CONFIRM_SLOT) {
            player.closeInventory();
            manager.depositItem(player, holder.sourceSlot(), holder.originalItem(), () -> {
                ResetVaultMenu.open(player, manager, messages, 0, -1);
            }, errorMsg -> {
                player.sendMessage(errorMsg);
            });
        }
    }

    // ------------------------------------------------------------------
    // BackupConfirmMenu Handlers
    // ------------------------------------------------------------------

    private void handleBackupConfirmMenuClick(InventoryClickEvent event, Player player, BackupConfirmMenu.Holder holder) {
        event.setCancelled(true);

        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        int slot = event.getSlot();
        if (slot == BackupConfirmMenu.CANCEL_SLOT) {
            player.closeInventory();
            player.sendMessage(messages.get(player, "reset-vault.backup.cancelled"));
            return;
        }

        if (slot == BackupConfirmMenu.CONFIRM_SLOT) {
            if (!holder.isArmed()) {
                holder.arm();
                BackupConfirmMenu.render(player, holder, holder.getInventory());
                player.sendMessage(messages.get(player, "reset-vault.backup.armed-chat"));
            } else {
                player.closeInventory();
                manager.startBackupProcess(player);
            }
        }
    }

    // ------------------------------------------------------------------
    // RestoreBrowserMenu Handlers
    // ------------------------------------------------------------------

    private void handleRestoreBrowserMenuClick(InventoryClickEvent event, Player player, RestoreBrowserMenu.Holder holder) {
        event.setCancelled(true);

        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        int slot = event.getSlot();
        if (slot == 45 && holder.page() > 0) {
            holder.setPage(holder.page() - 1);
            RestoreBrowserMenu.render(player, holder, holder.getInventory());
            return;
        }

        if (slot == 49) {
            player.closeInventory();
            return;
        }

        if (slot == 53) {
            holder.setPage(holder.page() + 1);
            RestoreBrowserMenu.render(player, holder, holder.getInventory());
            return;
        }

        if (slot >= 0 && slot < 45) {
            int index = holder.page() * 45 + slot;
            if (index < holder.history().size()) {
                ResetVaultStorage.BackupRecord record = holder.history().get(index);
                if (record.isSelectable()) {
                    RestoreConfirmMenu.open(player, manager, messages, record);
                } else {
                    player.sendMessage(messages.get(player, "reset-vault.restore.not-selectable"));
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // RestoreConfirmMenu Handlers
    // ------------------------------------------------------------------

    private void handleRestoreConfirmMenuClick(InventoryClickEvent event, Player player, RestoreConfirmMenu.Holder holder) {
        event.setCancelled(true);

        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        int slot = event.getSlot();
        if (slot == RestoreConfirmMenu.CANCEL_SLOT) {
            RestoreBrowserMenu.open(player, manager, messages, 0);
            return;
        }

        if (slot == RestoreConfirmMenu.CONFIRM_SLOT) {
            player.closeInventory();
            player.sendMessage(messages.get(player, "reset-vault.restore.started",
                    "id", String.valueOf(holder.record().id())));
            manager.executeRestore(player, holder.record().id()).thenAccept(success -> {
                if (success) {
                    player.sendMessage(messages.get(player, "reset-vault.restore.success",
                            "id", String.valueOf(holder.record().id())));
                } else {
                    player.sendMessage(messages.get(player, "reset-vault.restore.failed"));
                }
            });
        }
    }

    // ------------------------------------------------------------------
    // BlacklistEditorMenu Handlers
    // ------------------------------------------------------------------

    private void handleBlacklistEditorMenuClick(InventoryClickEvent event, Player player, BlacklistEditorMenu.Holder holder) {
        event.setCancelled(true);

        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        int slot = event.getSlot();
        if (slot == 45 && holder.page() > 0) {
            holder.setPage(holder.page() - 1);
            BlacklistEditorMenu.render(player, holder, holder.getInventory());
            return;
        }

        if (slot == 49) {
            player.closeInventory();
            return;
        }

        if (slot == 53) {
            holder.setPage(holder.page() + 1);
            BlacklistEditorMenu.render(player, holder, holder.getInventory());
            return;
        }

        if (slot >= 0 && slot < 45) {
            int itemIndex = holder.page() * 45 + slot;
            var entries = manager.blacklist();
            if (itemIndex < entries.size()) {
                // Left-click removes an entry
                if (event.getClick() == ClickType.LEFT) {
                    ResetVaultStorage.BlacklistEntry entry = entries.get(itemIndex);
                    manager.removeBlacklistEntry(entry.id()).thenRun(() -> {
                        Bukkit.getScheduler().runTask(manager.plugin(), () -> {
                            BlacklistEditorMenu.render(player, holder, holder.getInventory());
                            player.sendMessage(messages.get(player, "reset-vault.blacklist.removed",
                                    "key", entry.entryKey()));
                        });
                    });
                }
            } else if (itemIndex == entries.size()) {
                // Right-click the Add position opens selector
                if (event.getClick() == ClickType.RIGHT || event.getClick() == ClickType.LEFT) {
                    AdminItemSelectorMenu.open(player, manager, messages);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // AdminItemSelectorMenu Handlers
    // ------------------------------------------------------------------

    private void handleAdminItemSelectorMenuClick(InventoryClickEvent event, Player player, AdminItemSelectorMenu.Holder holder) {
        event.setCancelled(true);

        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        int slot = event.getSlot();
        if (slot == AdminItemSelectorMenu.BACK_SLOT) {
            BlacklistEditorMenu.open(player, manager, messages, 0);
            return;
        }

        if (slot >= 0 && slot < 36) {
            ItemStack clickedItem = player.getInventory().getItem(slot);
            if (clickedItem == null || clickedItem.getType().isAir()) {
                return;
            }

            String key = AdminItemSelectorMenu.determineBlacklistKey(clickedItem, manager);
            String type = AdminItemSelectorMenu.determineEntryType(clickedItem, manager);

            manager.addBlacklistEntry(type, key, "reset-vault.rejected.blacklisted").thenRun(() -> {
                Bukkit.getScheduler().runTask(manager.plugin(), () -> {
                    player.sendMessage(messages.get(player, "reset-vault.blacklist.added", "key", key));
                    BlacklistEditorMenu.open(player, manager, messages, 0);
                });
            });
        }
    }

    // ------------------------------------------------------------------
    // AdminVaultMenu Handlers
    // ------------------------------------------------------------------

    private void handleAdminVaultMenuClick(InventoryClickEvent event, Player adminPlayer, AdminVaultMenu.Holder holder) {
        event.setCancelled(true);

        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        int slot = event.getSlot();
        if (slot == 45 && holder.page() > 0) {
            holder.setPage(holder.page() - 1);
            AdminVaultMenu.render(adminPlayer, holder, holder.getInventory());
            return;
        }

        if (slot == 53) {
            holder.setPage(holder.page() + 1);
            AdminVaultMenu.render(adminPlayer, holder, holder.getInventory());
            return;
        }

        if (slot >= 0 && slot < 45) {
            int logicalIndex = holder.page() * 45 + slot;
            ResetVaultData data = manager.getVaultData(holder.targetUuid());

            // If admin has item on cursor: insert
            ItemStack cursor = event.getCursor();
            if (cursor != null && !cursor.getType().isAir()) {
                manager.adminInsert(holder.targetUuid(), logicalIndex, cursor.clone(), adminPlayer.getName());
                // FIX FOR ISS-47: Consume cursor item
                event.getView().setCursor(null);
                
                adminPlayer.sendMessage(messages.get(adminPlayer, "reset-vault.admin.inserted",
                        "slot", String.valueOf(logicalIndex)));
                AdminVaultMenu.render(adminPlayer, holder, holder.getInventory());
                return;
            }

            // Otherwise if slot has item: remove/pickup
            if (logicalIndex >= 0 && logicalIndex < data.itemCount()) {
                ItemStack item = data.items().get(logicalIndex);
                manager.adminRemove(holder.targetUuid(), logicalIndex, adminPlayer.getName());
                adminPlayer.getInventory().addItem(item.clone());
                adminPlayer.sendMessage(messages.get(adminPlayer, "reset-vault.admin.removed",
                        "slot", String.valueOf(logicalIndex)));
                AdminVaultMenu.render(adminPlayer, holder, holder.getInventory());
            }
        }
    }
}
