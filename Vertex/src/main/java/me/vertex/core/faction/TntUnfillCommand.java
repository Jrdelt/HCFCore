package me.vertex.core.faction;

import me.vertex.core.factions.FactionData;
import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Dispenser;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Returns TNT from nearby, owned dispensers directly to the faction TNT bank.
 * The command intentionally has no inventory destination: every successful
 * item removal is backed by a durable faction-bank deposit.
 */
public final class TntUnfillCommand implements CommandExecutor, TabCompleter, Listener {
    private static final int ABSOLUTE_MAX_RADIUS = 100;

    private final Plugin plugin;
    private final Messages messages;
    private final FactionBankManager bankManager;
    private final FactionUpgradeManager upgrades;
    private final Set<Integer> activeFactions = ConcurrentHashMap.newKeySet();
    private final Set<DispenserKey> lockedDispensers = ConcurrentHashMap.newKeySet();

    public TntUnfillCommand(Plugin plugin, Messages messages, FactionBankManager bankManager,
            FactionUpgradeManager upgrades) {
        this.plugin = plugin;
        this.messages = messages;
        this.bankManager = bankManager;
        this.upgrades = upgrades;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get(sender, "general.players-only"));
            return true;
        }
        if (!plugin.getConfig().getBoolean("tntunfill.enabled", true)) {
            player.sendMessage(messages.get(player, "tntunfill.disabled"));
            return true;
        }
        if (!player.hasPermission("vertex.tntunfill.use")) {
            player.sendMessage(messages.get(player, "general.no-permission"));
            return true;
        }

        int maxRadius = boundedRadius(plugin.getConfig().getInt("tntunfill.max-radius", ABSOLUTE_MAX_RADIUS));
        int radius = parseRadius(args, boundedRadius(plugin.getConfig().getInt("tntunfill.default-radius", maxRadius)), maxRadius);
        if (radius < 0) {
            player.sendMessage(messages.get(player, args.length > 0 && isInteger(args[0])
                    ? "tntunfill.invalid-radius" : "tntunfill.usage", "max", String.valueOf(maxRadius)));
            return true;
        }

        int factionId = FactionsHook.getFactionId(player);
        FactionData faction = FactionsHook.getFactionById(factionId).orElse(null);
        if (faction == null) {
            player.sendMessage(messages.get(player, "tntunfill.no-faction"));
            return true;
        }
        if (!FactionsHook.service().hasAction(FactionsHook.service().member(player.getUniqueId()), "tnt-fill")) {
            player.sendMessage(messages.get(player, "factions.role-permission-denied"));
            return true;
        }
        if (!activeFactions.add(factionId)) {
            player.sendMessage(messages.get(player, "tntunfill.processing"));
            return true;
        }

        long capacity = upgrades.tntCapacity(factionId);
        long current = bankManager.tnt(factionId);
        long headroom = current >= capacity ? 0L : capacity - current;
        if (headroom <= 0L) {
            activeFactions.remove(factionId);
            player.sendMessage(messages.get(player, "tntunfill.bank-full"));
            return true;
        }

        List<Dispenser> dispensers = TntFillCommand.findClaimedDispensers(player.getLocation(), radius, factionId);
        if (dispensers.isEmpty()) {
            activeFactions.remove(factionId);
            player.sendMessage(messages.get(player, "tntunfill.none-found"));
            return true;
        }
        ExtractionPlan plan = ExtractionPlan.create(dispensers, headroom);
        if (plan.total() <= 0L) {
            activeFactions.remove(factionId);
            player.sendMessage(messages.get(player, "tntunfill.none-found"));
            return true;
        }

        lock(plan);
        long removed = plan.remove();
        if (removed <= 0L) {
            unlock(plan);
            activeFactions.remove(factionId);
            player.sendMessage(messages.get(player, "tntunfill.none-found"));
            return true;
        }
        long deposited = removed;
        bankManager.depositTnt(player, factionId, deposited, capacity, "tnt-fill").whenComplete((saved, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        if (error != null || !Boolean.TRUE.equals(saved)) {
                            plan.restore();
                            player.sendMessage(messages.get(player, bankManager.tnt(factionId) >= capacity
                                    ? "tntunfill.bank-full" : "tntunfill.failed"));
                            return;
                        }
                        bankManager.audit(factionId, "TNT_UNFILL", player,
                                "amount=" + deposited + ";dispensers=" + plan.dispensersAffected());
                        player.sendMessage(messages.get(player, "tntunfill.unfilled",
                                "amount", String.valueOf(deposited),
                                "dispensers", String.valueOf(plan.dispensersAffected())));
                    } finally {
                        unlock(plan);
                        activeFactions.remove(factionId);
                    }
                }));
        return true;
    }

    static int parseRadius(String[] args, int defaultRadius, int maxRadius) {
        if (args.length == 0) return defaultRadius;
        if (args.length > 2 || (args.length == 2 && !args[1].equalsIgnoreCase("bank"))) return -1;
        try {
            int radius = Integer.parseInt(args[0]);
            return radius >= 1 && radius <= maxRadius ? radius : -1;
        } catch (NumberFormatException ignored) { return -1; }
    }

    private static boolean isInteger(String raw) {
        try { Integer.parseInt(raw); return true; }
        catch (NumberFormatException ignored) { return false; }
    }

    static int boundedRadius(int configured) {
        return Math.max(1, Math.min(ABSOLUTE_MAX_RADIUS, configured));
    }

    private void lock(ExtractionPlan plan) {
        plan.entries().forEach(entry -> lockedDispensers.add(DispenserKey.of(entry.dispenser())));
    }

    private void unlock(ExtractionPlan plan) {
        plan.entries().forEach(entry -> lockedDispensers.remove(DispenserKey.of(entry.dispenser())));
    }

    private boolean locked(Inventory inventory) {
        InventoryHolder holder = inventory == null ? null : inventory.getHolder();
        return holder instanceof Dispenser dispenser && lockedDispensers.contains(DispenserKey.of(dispenser));
    }

    private boolean locked(Block block) {
        return block != null && lockedDispensers.contains(DispenserKey.of(block.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (locked(event.getInventory())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (locked(event.getClickedInventory()) || locked(event.getView().getTopInventory())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (locked(event.getView().getTopInventory())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (locked(event.getSource()) || locked(event.getDestination())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (locked(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (locked(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(this::locked)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(this::locked)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::locked);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::locked);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 2) {
            return "bank".startsWith(args[1].toLowerCase(Locale.ROOT)) ? List.of("bank") : List.of();
        }
        return List.of();
    }

    private record DispenserKey(UUID worldId, int x, int y, int z) {
        private static DispenserKey of(Dispenser dispenser) { return of(dispenser.getLocation()); }
        private static DispenserKey of(Location location) {
            return new DispenserKey(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }
    }

    private static final class ExtractionPlan {
        private final List<Entry> entries;

        private ExtractionPlan(List<Entry> entries) { this.entries = entries; }

        static ExtractionPlan create(List<Dispenser> dispensers, long limit) {
            List<Entry> entries = new ArrayList<>();
            long remaining = limit;
            for (Dispenser dispenser : dispensers) {
                if (remaining <= 0L) break;
                int available = TntFillCommand.countTnt(dispenser.getInventory());
                int amount = (int) Math.min(available, remaining);
                if (amount > 0) {
                    entries.add(new Entry(dispenser, amount));
                    remaining -= amount;
                }
            }
            return new ExtractionPlan(entries);
        }

        List<Entry> entries() { return entries; }

        long total() {
            return entries.stream().mapToLong(Entry::planned).sum();
        }

        long remove() {
            long removed = 0L;
            for (Entry entry : entries) {
                int amount = removeTnt(entry.dispenser().getInventory(), entry.planned());
                entry.removed(amount);
                if (amount > 0) {
                    entry.dispenser().update();
                    removed += amount;
                }
            }
            return removed;
        }

        void restore() {
            for (Entry entry : entries) {
                int remaining = entry.removed();
                while (remaining > 0) {
                    int batch = Math.min(Material.TNT.getMaxStackSize(), remaining);
                    Map<Integer, ItemStack> leftovers = entry.dispenser().getInventory()
                            .addItem(new ItemStack(Material.TNT, batch));
                    int restored = batch - leftovers.values().stream().mapToInt(ItemStack::getAmount).sum();
                    if (restored != batch) {
                        throw new IllegalStateException("Locked dispenser did not retain room for TNT unfill rollback");
                    }
                    remaining -= restored;
                }
                if (entry.removed() > 0) entry.dispenser().update();
            }
        }

        int dispensersAffected() {
            return (int) entries.stream().filter(entry -> entry.removed() > 0).count();
        }

        private static int removeTnt(Inventory inventory, int wanted) {
            int remaining = wanted;
            for (int slot = 0; slot < inventory.getSize() && remaining > 0; slot++) {
                ItemStack item = inventory.getItem(slot);
                if (item == null || item.getType() != Material.TNT) continue;
                int taken = Math.min(remaining, item.getAmount());
                int after = item.getAmount() - taken;
                inventory.setItem(slot, after == 0 ? null : new ItemStack(Material.TNT, after));
                remaining -= taken;
            }
            return wanted - remaining;
        }

        private static final class Entry {
            private final Dispenser dispenser;
            private final int planned;
            private int removed;

            private Entry(Dispenser dispenser, int planned) {
                this.dispenser = dispenser;
                this.planned = planned;
            }

            Dispenser dispenser() { return dispenser; }
            int planned() { return planned; }
            int removed() { return removed; }
            void removed(int value) { removed = value; }
        }
    }
}
