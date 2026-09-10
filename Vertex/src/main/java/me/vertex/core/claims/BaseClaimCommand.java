package me.vertex.core.claims;

import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.Messages;
import me.vertex.core.menu.MenuRegistry;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.TabCompleteEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Owns Vertex's {@code /f baseclaim} view, the same "intercept one
 * FactionsUUID subcommand, let everything else pass through" pattern
 * {@code FTopCommand}/{@code PvpTopCommand} use.
 *
 * <p>Standing on an existing Base Claim (or on Wilderness/SafeZone/WarZone,
 * where there is nothing to create) opens the info/removal GUI. Standing on
 * a plain (Raid Claim) claim belonging to the player's own faction attempts
 * to create a new anchor there instead.
 */
public final class BaseClaimCommand implements Listener {

    private final Plugin plugin;
    private final BaseClaimManager baseClaims;
    private final Messages messages;
    private final MenuRegistry menus;

    public BaseClaimCommand(Plugin plugin, BaseClaimManager baseClaims, Messages messages, MenuRegistry menus) {
        this.plugin = plugin;
        this.baseClaims = baseClaims;
        this.messages = messages;
        this.menus = menus;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBaseClaim(PlayerCommandPreprocessEvent event) {
        String[] parts = event.getMessage().substring(1).trim().split("\\s+");
        if (parts.length < 2 || !isFactionCommand(event.getPlayer(), parts[0]) || !parts[1].equalsIgnoreCase("baseclaim")) {
            return;
        }
        event.setCancelled(true);
        if (parts.length >= 3 && parts[2].equalsIgnoreCase("buy")) {
            handlePurchase(event.getPlayer());
        } else {
            handle(event.getPlayer());
        }
    }

    /** Makes the intercepted /f baseclaim branch visible alongside FactionsUUID's own completions. */
    @EventHandler
    public void onFactionTabComplete(TabCompleteEvent event) {
        if (!(event.getSender() instanceof Player player) || !event.getBuffer().startsWith("/")) return;
        String[] parts = event.getBuffer().substring(1).split("\\s+", -1);
        if (parts.length == 2 && isFactionCommand(player, parts[0])) {
            addCompletion(event, parts[1], "baseclaim");
        } else if (parts.length == 3 && isFactionCommand(player, parts[0])
                && parts[1].equalsIgnoreCase("baseclaim")) {
            addCompletion(event, parts[2], "buy");
        }
    }

    private static void addCompletion(TabCompleteEvent event, String partial, String value) {
        if (!value.startsWith(partial.toLowerCase(Locale.ROOT))) return;
        List<String> completions = new ArrayList<>(event.getCompletions());
        if (completions.stream().noneMatch(value::equalsIgnoreCase)) completions.add(value);
        event.setCompletions(completions);
    }

    private void handlePurchase(Player player) {
        if (!player.hasPermission("vertex.baseclaim.purchaseslot")) {
            player.sendMessage(messages.get(player, "general.no-permission"));
            return;
        }
        int factionId = FactionsHook.getFactionId(player);
        if (factionId == FactionsHook.NO_FACTION) {
            player.sendMessage(messages.get(player, "baseclaim.no-faction"));
            return;
        }
        double price = baseClaims.priceForNextSlot(factionId);
        BaseClaimManager.PurchaseResult result = baseClaims.purchaseNextSlot(player, factionId);
        switch (result) {
            case OK -> player.sendMessage(messages.get(player, "baseclaim.purchase-ok",
                    "slot", String.valueOf(baseClaims.unlockedSlots(factionId)), "price", String.valueOf(price)));
            case ALL_UNLOCKED -> player.sendMessage(messages.get(player, "baseclaim.purchase-all-unlocked"));
            case NO_ECONOMY -> player.sendMessage(messages.get(player, "baseclaim.purchase-no-economy"));
            case CANNOT_AFFORD -> player.sendMessage(messages.get(player, "baseclaim.purchase-cannot-afford",
                    "price", String.valueOf(price)));
            case PERSIST_FAILED -> player.sendMessage(messages.get(player, "baseclaim.persist-failed"));
        }
    }

    private void handle(Player player) {
        if (!player.hasPermission("vertex.baseclaim.view")) {
            player.sendMessage(messages.get(player, "general.no-permission"));
            return;
        }
        int factionId = FactionsHook.getFactionId(player);
        if (factionId == FactionsHook.NO_FACTION) {
            player.sendMessage(messages.get(player, "baseclaim.no-faction"));
            return;
        }
        BaseClaimManager.Region region = baseClaims.regionAt(player.getLocation());
        if (region != null) {
            BaseClaimMenu.open(player, baseClaims, messages, menus, region);
            return;
        }
        if (FactionsHook.getClaimFactionId(player.getLocation()) != factionId) {
            player.sendMessage(messages.get(player, "baseclaim.not-your-claim"));
            return;
        }
        if (!player.hasPermission("vertex.baseclaim.create") || !FactionsHook.isLeader(player)) {
            player.sendMessage(messages.get(player, "baseclaim.leader-only"));
            return;
        }
        BaseClaimManager.CreateResult result = baseClaims.createAnchor(player, factionId, player.getLocation());
        switch (result) {
            case OK -> player.sendMessage(messages.get(player, "baseclaim.created"));
            case NO_SLOT_AVAILABLE -> player.sendMessage(messages.get(player, "baseclaim.no-slot",
                    "price", String.valueOf(baseClaims.priceForNextSlot(factionId))));
            case NOT_YOUR_FACTIONS_CLAIM -> player.sendMessage(messages.get(player, "baseclaim.not-your-claim"));
            case ALREADY_BASE_CLAIM -> player.sendMessage(messages.get(player, "baseclaim.already-base"));
            case PERSIST_FAILED -> player.sendMessage(messages.get(player, "baseclaim.persist-failed"));
        }
    }

    private boolean isFactionCommand(Player player, String command) {
        String normalized = command.contains(":") ? command.substring(command.indexOf(':') + 1) : command;
        return plugin.getConfig()
                .getStringList("factions.command-aliases").stream()
                .anyMatch(alias -> alias.equalsIgnoreCase(normalized));
    }
}
