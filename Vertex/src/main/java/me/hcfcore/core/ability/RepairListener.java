package me.hcfcore.core.ability;

import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.luckperms.LuckPermsHook;
import me.hcfcore.core.scoreboard.ScoreboardManager;
import me.hcfcore.core.user.UserManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.UUID;

public final class RepairListener implements Listener {

    private static final String ABILITY_ID = "repair";

    private final Plugin plugin;
    private final AbilityManager abilityManager;
    private final UserManager userManager;
    private final ScoreboardManager scoreboardManager;
    private final Messages messages;

    public RepairListener(Plugin plugin, AbilityManager abilityManager, UserManager userManager,
                           ScoreboardManager scoreboardManager, Messages messages) {
        this.plugin = plugin;
        this.abilityManager = abilityManager;
        this.userManager = userManager;
        this.scoreboardManager = scoreboardManager;
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

        if (!LuckPermsHook.isAvailable()) {
            player.sendMessage(messages.get(player, "ability.repair-no-luckperms"));
            return;
        }

        if (!AbilityGate.checkAndStart(plugin, abilityManager, userManager, messages, player, ability)) {
            return;
        }

        int durationSeconds = Math.max(1, ability.getInt("duration-seconds", 60));
        String permissionNode = ability.getString("permission-node", "essentials.fix");

        LuckPermsHook.grantTemporaryPermission(player, permissionNode, durationSeconds);
        player.sendMessage(messages.get(player, "ability.repair-granted", "seconds", String.valueOf(durationSeconds)));

        startCountdown(player.getUniqueId(), durationSeconds);
    }

    private void startCountdown(UUID uuid, int totalSeconds) {
        new BukkitRunnable() {
            int remaining = totalSeconds;

            @Override
            public void run() {
                Player player = Bukkit.getPlayer(uuid);
                if (player == null || remaining <= 0) {
                    if (player != null) {
                        scoreboardManager.clearPlaceholder(uuid, "repair");
                    }
                    cancel();
                    return;
                }
                scoreboardManager.setPlaceholder(uuid, "repair",
                        messages.getRaw(player, "ability.repair-countdown", "seconds", String.valueOf(remaining)));
                remaining--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }
}
