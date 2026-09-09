package me.vertex.core.gc;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

/**
 * Optional, config-gated notification hook fired whenever a player's own
 * action credits their GC balance (a deposit or a redeemed code) -- for
 * example, so a future Tebex webstore purchase confirmation, a Discord
 * webhook relay, or any other external system can be told "this much GC
 * just entered the economy for this player." Mirrors {@code
 * CaptureEventManager}'s existing reward-command idiom exactly:
 * {@code Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)} with
 * manual {@code {player}}/{@code {amount}} token substitution before dispatch.
 *
 * <p>A no-op when the configured template is blank (the default). This
 * hook is never load-bearing for the ledger itself -- GC's balance is
 * 100% self-hosted in {@link GcStorage} regardless of whether this is
 * configured, and every credit already happened before this is called.
 */
public final class GcInteropHook {

    private final Plugin plugin;
    private volatile String commandTemplate = "";

    public GcInteropHook(Plugin plugin) {
        this.plugin = plugin;
    }

    public void configure(String template) {
        this.commandTemplate = template == null ? "" : template.trim();
    }

    /** @param reason a short, stable word describing why GC was credited (e.g. "deposit", "redeem"). */
    public void onGcCredited(OfflinePlayer player, long amount, String reason) {
        String template = commandTemplate;
        if (template.isBlank()) {
            return;
        }
        String name = player.getName();
        if (name == null) {
            return;
        }
        String command = template.replace("{player}", name)
                .replace("{amount}", String.valueOf(amount))
                .replace("{reason}", reason == null ? "" : reason);
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (command.isBlank()) {
            return;
        }
        String finalCommand = command;
        // Dispatched on the main thread regardless of the caller's thread --
        // Bukkit.dispatchCommand must run there, and every GC mutation this
        // is wired to already runs its completion on the main thread anyway.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand)) {
                plugin.getLogger().warning("GC interop command reported failure: " + finalCommand);
            }
        });
    }
}
