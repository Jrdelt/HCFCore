package me.vertex.core.factions;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.vertex.core.claims.ChunkKey;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Handles native faction chat, autoclaim, persistent map mode, and online member name refresh. */
public final class FactionGameplayListener implements Listener {
    private final Plugin plugin;
    private final FactionService factions;
    private final Map<UUID, ChunkKey> lastMapChunk = new ConcurrentHashMap<>();

    public FactionGameplayListener(Plugin plugin, FactionService factions) { this.plugin = plugin; this.factions = factions; }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        factions.submitMutation(() -> { factions.updateOnlineName(event.getPlayer()); return true; });
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) { lastMapChunk.remove(event.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) return;
        Location from = event.getFrom();
        if ((from.getBlockX() >> 4 == to.getBlockX() >> 4)
                && (from.getBlockZ() >> 4 == to.getBlockZ() >> 4)
                && from.getWorld() == to.getWorld()) {
            return;
        }
        Player player = event.getPlayer();
        FactionStorage.PlayerSettings settings = factions.settings(player.getUniqueId());
        if (!settings.autoclaim() && !settings.mapEnabled()) {
            return;
        }
        ChunkKey target = ChunkKey.of(to);
        if (settings.autoclaim()) {
            factions.submitMutation(() -> factions.claim(player, target, factions.claimsMayOverclaim()));
        }
        if (settings.mapEnabled()) {
            if (!target.equals(lastMapChunk.put(player.getUniqueId(), target))) player.sendMessage(factions.map(player));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();
        String mode = factions.settings(sender.getUniqueId()).chatMode();
        if ((!mode.equals("FACTION") && !mode.equals("ALLY")) || FactionsHook.getFactionId(sender) == FactionsHook.NO_FACTION) return;
        Component message = event.message();
        event.setCancelled(true);
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(message);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (mode.equals("ALLY")) factions.sendAllyChat(sender, plain);
            else factions.sendFactionChat(sender, plain);
        });
    }
}
