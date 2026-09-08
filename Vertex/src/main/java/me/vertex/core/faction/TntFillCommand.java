package me.vertex.core.faction;

import dev.kitteh.factions.Faction;
import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
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
 * dispenser within radius blocks of the player up to the specified amount per dispenser,
 * drawing TNT from either the faction's Vertex TNT bank or the player's own inventory.
 * Only dispensers inside the player's own faction's claimed land count.
 */
public final class TntFillCommand implements CommandExecutor, TabCompleter {

    private final Plugin plugin;
    private final Messages messages;
    private final FactionBankManager bankManager;

    public TntFillCommand(Plugin plugin, Messages messages, FactionBankManager bankManager) {
        this.plugin = plugin;
        this.messages = messages;
        this.bankManager = bankManager;
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
        int targetPerDispenser;
        try {
            radius = Integer.parseInt(args[0]);
            targetPerDispenser = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(messages.get(player, "tntfill.usage"));
            return true;
        }
        if (radius < 1 || radius > maxRadius) {
            player.sendMessage(messages.get(player, "tntfill.invalid-radius", "max", String.valueOf(maxRadius)));
            return true;
        }
        if (targetPerDispenser < 1) {
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

        long available = fromBank ? bankManager.tnt(factionId) : countTnt(player);
        if (available <= 0) {
            player.sendMessage(messages.get(player, fromBank ? "tntfill.bank-empty" : "tntfill.inventory-empty"));
            return true;
        }

        List<Dispenser> dispensers = findClaimedDispensers(player.getLocation(), radius, factionId);
        if (dispensers.isEmpty()) {
            player.sendMessage(messages.get(player, "tntfill.none-found"));
            return true;
        }

        int reserve = (int) Math.min(available, demandOf(dispensers, targetPerDispenser));
        if (reserve <= 0) {
            player.sendMessage(messages.get(player, "tntfill.none-found"));
            return true;
        }

        if (!fromBank) {
            FillResult filled = fill(dispensers, targetPerDispenser, reserve);
            player.getInventory().removeItem(new ItemStack(Material.TNT, filled.placed()));
            report(player, dispensers.size(), targetPerDispenser, filled);
            return true;
        }

        // The bank is debited before a single dispenser is touched, so a
        // concurrent withdrawal elsewhere loses the race here rather than
        // letting this fill place TNT the faction no longer has. Anything the
        // dispensers could not actually take is returned immediately after.
        bankManager.withdrawTnt(factionId, reserve).whenComplete((withdrawn, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null || !Boolean.TRUE.equals(withdrawn)) {
                        player.sendMessage(messages.get(player, "tntfill.bank-empty"));
                        return;
                    }
                    FillResult filled = fill(dispensers, targetPerDispenser, reserve);
                    int unused = reserve - filled.placed();
                    if (unused > 0) {
                        bankManager.depositTnt(factionId, unused, Long.MAX_VALUE);
                    }
                    report(player, dispensers.size(), targetPerDispenser, filled);
                }));
        return true;
    }

    /** Total TNT the dispensers could still accept, so only that much is ever debited. */
    private static int demandOf(List<Dispenser> dispensers, int targetPerDispenser) {
        int demand = 0;
        for (Dispenser dispenser : dispensers) {
            demand += Math.max(0, targetPerDispenser - countTnt(dispenser.getInventory()));
        }
        return demand;
    }

    private static FillResult fill(List<Dispenser> dispensers, int targetPerDispenser, int budget) {
        int totalPlaced = 0;
        int dispensersFilled = 0;
        int remaining = budget;

        for (Dispenser dispenser : dispensers) {
            if (remaining <= 0) {
                break;
            }
            Inventory inventory = dispenser.getInventory();
            int toAdd = Math.min(targetPerDispenser - countTnt(inventory), remaining);
            if (toAdd <= 0) {
                continue;
            }
            Map<Integer, ItemStack> leftover = inventory.addItem(new ItemStack(Material.TNT, toAdd));
            int accepted = toAdd - leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
            if (accepted <= 0) {
                continue;
            }
            dispenser.update();
            totalPlaced += accepted;
            remaining -= accepted;
            dispensersFilled++;
        }
        return new FillResult(totalPlaced, dispensersFilled);
    }

    private void report(Player player, int totalDispensers, int targetPerDispenser, FillResult filled) {
        if (filled.placed() <= 0) {
            player.sendMessage(messages.get(player, "tntfill.none-found"));
            return;
        }
        int unfilled = totalDispensers - filled.dispensers();
        if (unfilled > 0 || filled.placed() < totalDispensers * targetPerDispenser) {
            player.sendMessage(messages.get(player, "tntfill.filled-partial",
                    "filled", String.valueOf(filled.dispensers()),
                    "unfilled", String.valueOf(Math.max(unfilled, 0))));
        } else {
            player.sendMessage(messages.get(player, "tntfill.filled",
                    "amount", String.valueOf(filled.placed()),
                    "dispensers", String.valueOf(filled.dispensers())));
        }
    }

    private record FillResult(int placed, int dispensers) {
    }

    private static int countTnt(Inventory inventory) {
        int count = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.getType() == Material.TNT) {
                count += item.getAmount();
            }
        }
        return count;
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

    private static List<Dispenser> findClaimedDispensers(Location center, int radius, int factionId) {
        var world = center.getWorld();
        if (world == null) {
            return List.of();
        }
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