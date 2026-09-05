package me.hcfcore.core.pvp;

import me.hcfcore.core.factions.FactionsHook;
import me.hcfcore.core.kit.ArmorClass;
import me.hcfcore.core.lang.MessageFormatter;
import net.kyori.adventure.text.Component;
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

    // Cached config values to avoid repeated reads on hot path
    private volatile int cachedDurationSeconds;
    private volatile int cachedMaxStacks;
    private volatile double cachedArrowBonusPerStack;
    private volatile double cachedMeleeBonusPerStack;
    private volatile String cachedMessageAttacker;
    private volatile String cachedMessageVictim;

    public ArcherTagListener(Plugin plugin, ArcherTagManager archerTagManager) {
        this.plugin = plugin;
        this.archerTagManager = archerTagManager;
        reloadConfig();
    }

    public void reloadConfig() {
        cachedDurationSeconds = plugin.getConfig().getInt("pvp.archer-tag.duration-seconds", 10);
        cachedMaxStacks = plugin.getConfig().getInt("pvp.archer-tag.max-stacks", 4);
        cachedArrowBonusPerStack = Math.max(0, plugin.getConfig().getDouble("pvp.archer-tag.arrow-damage-bonus-per-stack", 0.10));
        cachedMeleeBonusPerStack = Math.max(0, plugin.getConfig().getDouble("pvp.archer-tag.faction-melee-bonus-per-stack", 0.05));
        cachedMessageAttacker = plugin.getConfig().getString("pvp.archer-tag.message-attacker",
                "&c&lKITS&r &7> <gold>Tagged {player} <gray>(+{percent}%, {seconds}s)");
        cachedMessageVictim = plugin.getConfig().getString("pvp.archer-tag.message-victim",
                "&c&lKITS&r &7> <red>Tagged by {player} <gray>(+{percent}%, {seconds}s)");
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

        shooter.sendMessage(renderTagMessage(cachedMessageAttacker, victim.getName(), arrowPercent, meleePercent, seconds));
        victim.sendMessage(renderTagMessage(cachedMessageVictim, shooter.getName(), arrowPercent, meleePercent, seconds));
    }

    /**
     * Built from a config.yml template, not a lang key -- like the combat
     * action bar templates, this is a single admin-authored string rather
     * than a per-locale message, since {player}'s name can't contain
     * MiniMessage syntax (Minecraft restricts the character set) and so
     * needs no escaping either.
     */
    private static Component renderTagMessage(String template, String player, String arrowPercent,
                                               String meleePercent, long seconds) {
        String resolved = template
                .replace("{player}", player)
                .replace("{percent}", arrowPercent)
                .replace("{melee}", meleePercent)
                .replace("{seconds}", String.valueOf(seconds));
        return MessageFormatter.deserialize(resolved);
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
