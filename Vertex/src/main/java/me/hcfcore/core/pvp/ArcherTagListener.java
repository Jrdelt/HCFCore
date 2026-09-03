package me.hcfcore.core.pvp;

import me.hcfcore.core.factions.FactionsHook;
import me.hcfcore.core.kit.ArmorClass;
import me.hcfcore.core.lang.Messages;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * The archer class's damage-over-time-by-committee mechanic: an archer's
 * arrows leave the victim "archer tagged", and every further arrow lands
 * harder while the tag is up. The archer's own faction also gets a smaller
 * melee bonus against the tagged player, so a team can capitalize on their
 * archer's chip damage -- but only that team, not everyone on the map.
 */
public final class ArcherTagListener implements Listener {

    private final Plugin plugin;
    private final ArcherTagManager archerTagManager;
    private final Messages messages;

    // Cached config values to avoid repeated reads on hot path
    private volatile int cachedDurationSeconds;
    private volatile int cachedMaxStacks;
    private volatile double cachedArrowBonusPerStack;
    private volatile double cachedMeleeBonusPerStack;

    public ArcherTagListener(Plugin plugin, ArcherTagManager archerTagManager, Messages messages) {
        this.plugin = plugin;
        this.archerTagManager = archerTagManager;
        this.messages = messages;
        reloadConfig();
    }

    public void reloadConfig() {
        cachedDurationSeconds = plugin.getConfig().getInt("pvp.archer-tag.duration-seconds", 10);
        cachedMaxStacks = plugin.getConfig().getInt("pvp.archer-tag.max-stacks", 4);
        cachedArrowBonusPerStack = Math.max(0, plugin.getConfig().getDouble("pvp.archer-tag.arrow-damage-bonus-per-stack", 0.15));
        cachedMeleeBonusPerStack = Math.max(0, plugin.getConfig().getDouble("pvp.archer-tag.faction-melee-bonus-per-stack", 0.05));
    }

    /**
     * HIGH so the multiplier lands on damage other plugins have already
     * adjusted, and so a cancelled hit never applies a tag.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (event.getDamager() instanceof AbstractArrow arrow) {
            onArrowHit(event, arrow, victim);
        } else if (event.getDamager() instanceof Player attacker) {
            onMeleeHit(event, attacker, victim);
        }
    }

    private void onArrowHit(EntityDamageByEntityEvent event, AbstractArrow arrow, Player victim) {
        if (!(arrow.getShooter() instanceof Player shooter)
                || shooter.getUniqueId().equals(victim.getUniqueId())
                || !ArmorClass.isArcher(shooter)
                || FactionsHook.isSameFaction(shooter, victim)) {
            return;
        }

        // The stacks already on the victim scale this arrow; the stack this
        // hit adds only counts from the next arrow onward, so the shot that
        // opens the tag deals normal damage.
        int existingStacks = archerTagManager.stacks(victim.getUniqueId());
        if (existingStacks > 0) {
            event.setDamage(event.getDamage() * (1 + existingStacks * arrowBonusPerStack()));
        }

        int stacks = archerTagManager.tag(victim.getUniqueId(), FactionsHook.getFactionId(shooter),
                durationSeconds(), maxStacks());
        if (stacks <= 0) {
            return;
        }
        announce(shooter, victim, stacks);
    }

    private void onMeleeHit(EntityDamageByEntityEvent event, Player attacker, Player victim) {
        int stacks = archerTagManager.stacksFor(victim.getUniqueId(), FactionsHook.getFactionId(attacker));
        if (stacks > 0) {
            event.setDamage(event.getDamage() * (1 + stacks * meleeBonusPerStack()));
        }
    }

    private void announce(Player shooter, Player victim, int stacks) {
        long seconds = (archerTagManager.remainingMillis(victim.getUniqueId()) + 999L) / 1000L;
        String arrowPercent = percent(stacks * arrowBonusPerStack());
        String meleePercent = percent(stacks * meleeBonusPerStack());

        shooter.sendMessage(messages.getChat(shooter, "archer.tag-applied",
                "player", victim.getName(),
                "percent", arrowPercent,
                "melee", meleePercent,
                "seconds", String.valueOf(seconds)));
        victim.sendMessage(messages.getChat(victim, "archer.tag-received",
                "player", shooter.getName(),
                "percent", arrowPercent,
                "melee", meleePercent,
                "seconds", String.valueOf(seconds)));
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        archerTagManager.clear(event.getEntity().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        archerTagManager.clearIfExpired(event.getPlayer().getUniqueId());
    }

    private int durationSeconds() {
        return cachedDurationSeconds;
    }

    private int maxStacks() {
        return cachedMaxStacks;
    }

    private double arrowBonusPerStack() {
        return cachedArrowBonusPerStack;
    }

    private double meleeBonusPerStack() {
        return cachedMeleeBonusPerStack;
    }

    /** Whole-percent string for the messages, e.g. 0.45 -> "45". */
    private static String percent(double fraction) {
        return String.valueOf(Math.round(fraction * 100));
    }
}
