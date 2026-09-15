package me.vertex.core.resetvault;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Subscribes to SOTW orchestrator milestones and transitions Reset Vault to WITHDRAW.
 */
public final class SotwMilestoneSubscriber implements Listener {

    private final ResetVaultManager manager;

    public SotwMilestoneSubscriber(ResetVaultManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMilestone(SotwMilestoneEvent event) {
        if (event.milestoneName() != null && event.milestoneName().equalsIgnoreCase(manager.sotwMilestoneName())) {
            if (manager.phase() != ResetVaultPhase.RECOVERY_LOCKED && manager.phase() != ResetVaultPhase.BACKUP_RUNNING) {
                manager.setPhase(ResetVaultPhase.WITHDRAW);
                Bukkit.broadcast(manager.messages().get(Bukkit.getConsoleSender(), "reset-vault.sotw.unlocked"));
            }
        }
    }
}
