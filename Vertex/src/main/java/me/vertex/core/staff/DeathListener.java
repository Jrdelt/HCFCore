package me.vertex.core.staff;

import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

public final class DeathListener implements Listener {

    private final DeathManager deathManager;
    private final Messages messages;

    public DeathListener(DeathManager deathManager, Messages messages) {
        this.deathManager = deathManager;
        this.messages = messages;
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        String cause = "UNKNOWN";
        if (player.getLastDamageCause() != null && player.getLastDamageCause().getCause() != null) {
            cause = player.getLastDamageCause().getCause().toString();
        }

        String killer = null;
        if (player.getKiller() != null) {
            killer = player.getKiller().getName();
        }

        Death death = Death.from(player, cause, killer);
        deathManager.saveDeath(player.getUniqueId(), death);
        event.deathMessage(deathMessage(player, killer, cause));
    }

    private Component deathMessage(Player victim, String killer, String cause) {
        String key;
        if (killer != null) {
            key = "death.player-killed";
        } else {
            key = switch (cause) {
                case "LAVA" -> "death.lava";
                case "FALL" -> "death.fall";
                case "VOID" -> "death.void";
                case "FIRE", "FIRE_TICK", "HOT_FLOOR" -> "death.fire";
                case "DROWNING" -> "death.drowning";
                case "SUFFOCATION" -> "death.suffocation";
                default -> "death.generic";
            };
        }
        return messages.get(victim, key,
                "victim", victim.getName(),
                "killer", killer == null ? "" : killer,
                "cause", cause.toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
    }
}
