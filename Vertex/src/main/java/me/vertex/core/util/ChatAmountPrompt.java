package me.vertex.core.util;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Captures a player's next chat line as a numeric amount, instead of an
 * anvil's rename-text field.
 *
 * <p>The anvil approach this replaces reads {@code getRenameText()} off a
 * container whose state vanilla itself resets as part of processing a
 * click on the result slot -- a race that survived two rounds of
 * increasingly specific workarounds (caching the last good read, storing
 * the parsed value in the result item's own PDC, re-ordering when the
 * repair-cost override was applied) and still failed in practice. Chat
 * input has none of that: a message is either delivered to this listener
 * or it isn't, with no client-side container state to get out of sync
 * with the server's.
 *
 * <p>One instance is created and registered once in {@code VertexPlugin};
 * every feature that needs a typed amount ({@code ChunkCollectorMenuListener},
 * {@code FactionBankMenu}) is handed the same instance rather than each
 * rolling its own chat capture.
 */
public final class ChatAmountPrompt implements Listener {

    private static final long TIMEOUT_TICKS = 20L * 60; // 60 seconds

    private final Plugin plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public ChatAmountPrompt(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * @param prompt   sent immediately, describing what to type.
     * @param onAmount run on the main thread with the parsed, positive amount.
     * @param onCancel run (also on the main thread) if the player types
     *                 "cancel", the prompt times out, or a new prompt
     *                 replaces this one before it was answered. Never run
     *                 if {@code onAmount} already was.
     */
    public void request(Player player, Component prompt, Consumer<Long> onAmount, Runnable onCancel) {
        cancelSilently(player.getUniqueId());
        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (pending.remove(player.getUniqueId()) != null && player.isOnline()) {
                player.sendMessage(Component.text("Timed out waiting for an amount.", NamedTextColor.RED));
                onCancel.run();
            }
        }, TIMEOUT_TICKS);
        pending.put(player.getUniqueId(), new Pending(onAmount, onCancel, timeout));
        player.sendMessage(prompt);
    }

    /** True while this player has an unanswered prompt -- lets a GUI avoid reopening under it. */
    public boolean isPending(UUID uuid) {
        return pending.containsKey(uuid);
    }

    /** Cancels a pending prompt for this player, if any, running its onCancel callback. */
    public void cancel(Player player) {
        Pending removed = pending.remove(player.getUniqueId());
        if (removed != null) {
            removed.timeout().cancel();
            removed.onCancel().run();
        }
    }

    private void cancelSilently(UUID uuid) {
        Pending removed = pending.remove(uuid);
        if (removed != null) {
            removed.timeout().cancel();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Pending prompt = pending.get(player.getUniqueId());
        if (prompt == null) {
            return;
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

        if (text.equalsIgnoreCase("cancel")) {
            pending.remove(player.getUniqueId());
            prompt.timeout().cancel();
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.sendMessage(Component.text("Cancelled.", NamedTextColor.GRAY));
                prompt.onCancel().run();
            });
            return;
        }

        Long amount = AmountParser.parse(text);
        if (amount == null) {
            // Stays pending -- let them try again rather than forcing a
            // full restart of whatever menu flow led here over one typo.
            Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(Component.text(
                    "Not a valid amount. Type a number (e.g. 100k, 1.5m), or 'cancel'.", NamedTextColor.RED)));
            return;
        }

        pending.remove(player.getUniqueId());
        prompt.timeout().cancel();
        Bukkit.getScheduler().runTask(plugin, () -> prompt.onAmount().accept(amount));
    }

    /** Running a command mid-prompt is treated as walking away from it, not answering it. */
    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        cancelIfPending(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancelSilently(event.getPlayer().getUniqueId());
    }

    private void cancelIfPending(Player player) {
        Pending removed = pending.remove(player.getUniqueId());
        if (removed != null) {
            removed.timeout().cancel();
            Bukkit.getScheduler().runTask(plugin, removed.onCancel());
        }
    }

    private record Pending(Consumer<Long> onAmount, Runnable onCancel, BukkitTask timeout) {
    }
}
