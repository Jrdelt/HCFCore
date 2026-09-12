package me.vertex.core.claims;

import me.vertex.core.factions.FactionMember;
import me.vertex.core.factions.FactionRole;
import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.plugin.Plugin;

/** Revalidates every Base slot click before assigning or removing an anchor. */
public final class BaseClaimMenuListener implements Listener {
    private final Plugin plugin;
    private final BaseClaimManager manager;
    private final Messages messages;

    public BaseClaimMenuListener(Plugin plugin, BaseClaimManager manager, Messages messages) {
        this.plugin = plugin; this.manager = manager; this.messages = messages;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof BaseClaimMenu.Holder) event.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof BaseClaimMenu.Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() != event.getView().getTopInventory()) return;
        FactionMember member = FactionsHook.service().member(player.getUniqueId());
        if (member == null || member.factionId() != holder.factionId()) {
            player.closeInventory();
            player.sendMessage(messages.get(player, "baseclaim.changed"));
            return;
        }
        if (holder.view() == BaseClaimMenu.View.OVERVIEW) {
            int position = BaseClaimMenu.SLOT_POSITIONS.indexOf(event.getSlot());
            if (position < 0) return;
            int slot = position + 1;
            if (slot > manager.unlockedSlots(holder.factionId())) return;
            BaseClaimManager.Region region = manager.region(holder.factionId(), slot);
            if (region == null && event.isRightClick()) assign(player, holder.factionId(), slot);
            else if (region != null && event.isLeftClick()) {
                if (!canManage(member)) {
                    player.sendMessage(messages.get(player, "baseclaim.leader-only"));
                    return;
                }
                BaseClaimMenu.openConfirmation(player, messages, holder.factionId(), slot, 1);
            }
            return;
        }
        if (event.getSlot() == 15) {
            player.closeInventory();
            player.sendMessage(messages.get(player, "baseclaim.cancelled"));
            return;
        }
        if (event.getSlot() != 11 || !canManage(member)) return;
        if (manager.region(holder.factionId(), holder.slotIndex()) == null) {
            player.closeInventory();
            player.sendMessage(messages.get(player, "baseclaim.changed"));
            return;
        }
        if (holder.view() == BaseClaimMenu.View.CONFIRM_ONE) {
            BaseClaimMenu.openConfirmation(player, messages, holder.factionId(), holder.slotIndex(), 2);
            return;
        }
        player.closeInventory();
        manager.removeAnchorAsync(player, holder.factionId(), holder.slotIndex()).whenComplete((result, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null || result == BaseClaimManager.RemoveResult.PERSIST_FAILED) {
                        player.sendMessage(messages.get(player, "baseclaim.persist-failed"));
                        return;
                    }
                    if (result != BaseClaimManager.RemoveResult.OK) {
                        String key = switch (result) {
                            case SHIELDED -> "baseclaim.remove-shielded";
                            case HAS_SPAWNERS -> "baseclaim.remove-has-spawners";
                            case NOT_AUTHORIZED -> "baseclaim.leader-only";
                            default -> "baseclaim.changed";
                        };
                        player.sendMessage(messages.get(player, key));
                        return;
                    }
                    for (FactionMember factionMember : FactionsHook.service().members(holder.factionId())) {
                        Player online = Bukkit.getPlayer(factionMember.playerUuid());
                        if (online != null) online.sendMessage(messages.get(online,
                                "baseclaim.removed-broadcast", "slot", String.valueOf(holder.slotIndex())));
                    }
                }));
    }

    private void assign(Player player, int factionId, int slot) {
        FactionMember member = FactionsHook.service().member(player.getUniqueId());
        if (!canManage(member)) {
            player.sendMessage(messages.get(player, "baseclaim.leader-only"));
            return;
        }
        if (FactionsHook.getClaimFactionId(player.getLocation()) != factionId) {
            player.sendMessage(messages.get(player, "baseclaim.not-your-claim"));
            return;
        }
        var location = player.getLocation().clone();
        player.closeInventory();
        manager.createAnchorAsync(player, factionId, location, slot).whenComplete((result, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        player.sendMessage(messages.get(player, "baseclaim.persist-failed"));
                        return;
                    }
                    switch (result) {
                        case OK -> player.sendMessage(messages.get(player, "baseclaim.created-slot",
                                "slot", String.valueOf(slot)));
                        case NOT_YOUR_FACTIONS_CLAIM -> player.sendMessage(messages.get(player, "baseclaim.not-your-claim"));
                        case ALREADY_BASE_CLAIM -> player.sendMessage(messages.get(player, "baseclaim.already-base"));
                        case NO_SLOT_AVAILABLE -> player.sendMessage(messages.get(player, "baseclaim.no-slot"));
                        case NOT_AUTHORIZED -> player.sendMessage(messages.get(player, "baseclaim.leader-only"));
                        case STATE_CHANGED -> player.sendMessage(messages.get(player, "baseclaim.changed"));
                        case PERSIST_FAILED -> player.sendMessage(messages.get(player, "baseclaim.persist-failed"));
                        case BUSY -> player.sendMessage(messages.get(player, "baseclaim.busy"));
                    }
                }));
    }

    private static boolean canManage(FactionMember member) {
        return member != null && (member.role() == FactionRole.LEADER || member.role() == FactionRole.COLEADER);
    }
}
