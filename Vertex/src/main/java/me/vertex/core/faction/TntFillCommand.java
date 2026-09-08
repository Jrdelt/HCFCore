package me.vertex.core.faction;

import dev.kitteh.factions.Faction;
import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.Messages;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Dispenser;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@code /tntfill <radius> <amount> <bank|inventory>}: tops up every
 * dispenser within radius blocks of the player, drawing TNT from either
 * FactionsUUID's own native TNT bank ({@link Faction#tntBank()} -- the
 * same balance {@code /f tnt}/{@code /f tntdeposit}/{@code /f tntwithdraw}
 * manage, deliberately reused rather than inventing a second, competing
 * Vertex-side TNT ledger) or the player's own inventory. Only dispensers
 * inside the player's own faction's claimed land count.
 */
public final class TntFillCommand implements CommandExecutor, TabCompleter {

    private final Plugin plugin;
    private final Messages messages;

    public TntFillCommand(Plugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get(sender, "general.players-only"));
            return true;
        }
        if (!plugin.getConfig().getBoolean("tntfill.enabled", true)) {
            player.sendMessage(messages.get(player, "tntfill.disabled"));
            return true;
        }
        if (!player.hasPermission("vertex.tntfill.use")) {
            player.sendMessage(messages.get(player, "general.no-permission"));
            return true;
        }
        if (args.length != 3) {
            player.sendMessage(messages.get(player, "tntfill.usage"));
            return true;
        }

        int maxRadius = Math.min(100, plugin.getConfig().getInt("tntfill.max-radius", 100));
        int radius;
        int amount;
        try {
            radius = Integer.parseInt(args[0]);
            amount = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(messages.get(player, "tntfill.usage"));
            return true;
        }
        if (radius < 1 || radius > maxRadius) {
            player.sendMessage(messages.get(player, "tntfill.invalid-radius", "max", String.valueOf(maxRadius)));
            return true;
        }
        if (amount < 1) {
            player.sendMessage(messages.get(player, "tntfill.invalid-amount"));
            return true;
        }

        boolean fromBank;
        if (args[2].equalsIgnoreCase("bank")) {
            fromBank = true;
        } else if (args[2].equalsIgnoreCase("inventory")) {
            fromBank = false;
        } else {
            player.sendMessage(messages.get(player, "tntfill.usage"));
            return true;
        }

        int factionId = FactionsHook.getFactionId(player);
        Faction faction = FactionsHook.getFactionById(factionId);
        if (faction == null) {
            player.sendMessage(messages.get(player, "tntfill.no-faction"));
            return true;
        }

        int available = fromBank ? faction.tntBank() : countTnt(player);
        int toDistribute = Math.min(amount, available);
        if (toDistribute <= 0) {
            player.sendMessage(messages.get(player, fromBank ? "tntfill.bank-empty" : "tntfill.inventory-empty"));
            return true;
        }

        List<Dispenser> dispensers = findClaimedDispensers(player.getLocation(), radius, factionId);
        if (dispensers.isEmpty()) {
            player.sendMessage(messages.get(player, "tntfill.none-found"));
            return true;
        }

        int placed = 0;
        int dispensersFilled = 0;
        for (Dispenser dispenser : dispensers) {
            if (toDistribute <= 0) {
                break;
            }
            int attempt = Math.min(toDistribute, 64);
            Inventory inventory = dispenser.getInventory();
            Map<Integer, ItemStack> leftover = inventory.addItem(new ItemStack(Material.TNT, attempt));
            int accepted = attempt - leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
            if (accepted <= 0) {
                continue;
            }
            dispenser.update();
            placed += accepted;
            toDistribute -= accepted;
            dispensersFilled++;
        }

        if (placed <= 0) {
            player.sendMessage(messages.get(player, "tntfill.none-found"));
            return true;
        }

        if (fromBank) {
            faction.tntBank(faction.tntBank() - placed);
        } else {
            player.getInventory().removeItem(new ItemStack(Material.TNT, placed));
        }

        player.sendMessage(messages.get(player, "tntfill.filled",
                "amount", String.valueOf(placed), "dispensers", String.valueOf(dispensersFilled)));
        return true;
    }

    private static int countTnt(Player player) {
        int count = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.getType() == Material.TNT) {
                count += item.getAmount();
            }
        }
        return count;
    }

    /**
     * Scans loaded chunks within {@code radius} blocks of {@code center}
     * (a chunk-grid superset of the true radius, narrowed below by an
     * exact distance check) for dispensers, via each chunk's own tile-
     * entity list rather than a block-by-block scan -- the only way a
     * 100-block radius (up to ~169 chunks) stays cheap. Unloaded chunks
     * are skipped rather than force-loaded, matching how other "find
     * nearby X" tools in Vertex behave.
     */
    private static List<Dispenser> findClaimedDispensers(Location center, int radius, int factionId) {
        var world = center.getWorld();
        int centerChunkX = center.getBlockX() >> 4;
        int centerChunkZ = center.getBlockZ() >> 4;
        int chunkRadius = (radius >> 4) + 1;

        List<Dispenser> found = new ArrayList<>();
        for (int cx = centerChunkX - chunkRadius; cx <= centerChunkX + chunkRadius; cx++) {
            for (int cz = centerChunkZ - chunkRadius; cz <= centerChunkZ + chunkRadius; cz++) {
                if (!world.isChunkLoaded(cx, cz)) {
                    continue;
                }
                Chunk chunk = world.getChunkAt(cx, cz);
                for (var state : chunk.getTileEntities(block -> block.getType() == Material.DISPENSER, false)) {
                    if (!(state instanceof Dispenser dispenser)) {
                        continue;
                    }
                    Location loc = dispenser.getLocation();
                    if (loc.distance(center) > radius) {
                        continue;
                    }
                    if (FactionsHook.getClaimFactionId(loc) != factionId) {
                        continue;
                    }
                    found.add(dispenser);
                }
            }
        }
        found.sort((a, b) -> Double.compare(a.getLocation().distanceSquared(center), b.getLocation().distanceSquared(center)));
        return found;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 3) {
            String partial = args[2].toLowerCase(Locale.ROOT);
            return Stream.of("bank", "inventory")
                    .filter(sub -> sub.startsWith(partial))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
