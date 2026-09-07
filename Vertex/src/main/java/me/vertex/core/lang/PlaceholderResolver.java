package me.vertex.core.lang;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.essentials.EssentialsHook;
import me.vertex.core.factions.FactionsHook;
import me.vertex.core.luckperms.LuckPermsHook;
import me.vertex.core.placeholderapi.PlaceholderApiHook;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Resolves the {curly}-placeholder set shared by every per-player text
 * template in the plugin -- the sidebar scoreboard and the tab list both
 * use it -- then layers PlaceholderAPI's own {@code %percent%} expansion
 * on top as a final pass. Kept in one place so a new placeholder, or a
 * fix to an existing one (e.g. how {@code {rank_prefix}} gets bracketed),
 * only has to happen once instead of drifting between call sites.
 */
public final class PlaceholderResolver {

    private PlaceholderResolver() {
    }

    /**
     * @param onlineCount   only computed by the caller when the template
     *                      actually uses {@code {online}} -- counting online
     *                      players excluding vanished staff isn't free at a
     *                      real player count, so callers check first.
     * @param factionTop    this player's faction's power-ranking position, or
     *                      {@code null} if the caller didn't compute one
     *                      (e.g. no configured template uses {@code {ftop}}).
     * @param dateFormatter the {@code {date}} format; callers own this since
     *                      it's configured per-feature (scoreboard vs. tab
     *                      list could format the date differently).
     * @param custom        extra key/value substitutions on top of the
     *                      built-in set, applied as {@code {key}} -- e.g.
     *                      the Repair ability's countdown feeding a
     *                      {@code {repair}} token into the scoreboard.
     */
    public static String resolve(Player player, String template, int onlineCount, String factionTop,
            DateTimeFormatter dateFormatter, Map<String, String> custom) {
        // Every substitution is guarded on the *original* template (not the
        // progressively-substituted result, which a pathological faction
        // name or nickname could otherwise make contain a literal
        // "{placeholder}"-looking substring of its own) containing that
        // exact token first. This isn't just an optimization: several of
        // these calls reach into FactionsUUID's/LuckPerms'/Vault's own
        // live state, which is unsafe to touch at all when that plugin
        // isn't actually running -- a template that never asks for
        // {faction} shouldn't pay for (or risk) resolving it.
        String resolved = template;
        if (template.contains("{date}")) {
            resolved = resolved.replace("{date}", LocalDate.now().format(dateFormatter));
        }
        if (template.contains("{online}")) {
            resolved = resolved.replace("{online}", String.valueOf(onlineCount));
        }
        if (template.contains("{name}")) {
            resolved = resolved.replace("{name}", EssentialsHook.resolveName(player));
        }
        if (template.contains("{rank_prefix}") || template.contains("{rank}")) {
            String rank = LuckPermsHook.getPrimaryGroupDisplayName(player);
            String rankPrefix = rank == null || rank.isBlank() ? "" : "[" + rank + "] ";
            resolved = resolved.replace("{rank_prefix}", rankPrefix).replace("{rank}", rank == null ? "" : rank);
        }
        if (template.contains("{prefix}")) {
            String prefix = LuckPermsHook.getPrefix(player);
            resolved = resolved.replace("{prefix}", prefix == null ? "" : prefix);
        }
        if (template.contains("{exp}")) {
            resolved = resolved.replace("{exp}", String.valueOf(player.getLevel()));
        }
        if (template.contains("{balance}")) {
            resolved = resolved.replace("{balance}", EconomyHook.getBalance(player));
        }
        if (template.contains("{faction}")) {
            resolved = resolved.replace("{faction}", FactionsHook.getFactionTag(player));
        }
        if (template.contains("{faction_role}")) {
            resolved = resolved.replace("{faction_role}", FactionsHook.getRoleName(player));
        }
        if (template.contains("{ftop}")) {
            resolved = resolved.replace("{ftop}", factionTop == null ? "" : factionTop);
        }
        if (template.contains("{power}")) {
            resolved = resolved.replace("{power}", FactionsHook.getFactionPower(player));
        }
        if (template.contains("{fplayers_online}")) {
            resolved = resolved.replace("{fplayers_online}", FactionsHook.getOnlineFactionCount(player));
        }
        for (Map.Entry<String, String> entry : custom.entrySet()) {
            String token = "{" + entry.getKey() + "}";
            if (template.contains(token)) {
                resolved = resolved.replace(token, entry.getValue());
            }
        }
        // Applied last so a template can mix the {curly} placeholders above
        // with any %percent% PlaceholderAPI expansion (e.g. %luckperms_prefix%).
        return PlaceholderApiHook.apply(player, resolved);
    }
}
