package me.vertex.core.redstone;

import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Powerable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Records the last successful player use of a lever or button for admin replay. */
public final class LastRedstoneCommand implements CommandExecutor, Listener {

    private static final String PERMISSION = "vertex.redstone.replay";

    private final Plugin plugin;
    private final Messages messages;
    private final Map<BlockKey, BukkitTask> buttonReleaseTasks = new HashMap<>();
    private volatile LastControl lastControl;

    public LastRedstoneCommand(Plugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerUseControl(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null || !isControl(block.getType())) return;
        lastControl = new LastControl(BlockKey.from(block), block.getType(), event.getPlayer().getUniqueId());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(messages.get(sender, "general.no-permission"));
            return true;
        }
        if (args.length != 0) {
            sender.sendMessage(messages.get(sender, "redstone.usage"));
            return true;
        }

        LastControl recorded = lastControl;
        if (recorded == null) {
            sender.sendMessage(messages.get(sender, "redstone.no-last-control"));
            return true;
        }
        Block block = resolve(recorded);
        if (block == null) {
            sender.sendMessage(messages.get(sender, "redstone.last-control-unavailable"));
            return true;
        }

        if (isButton(block.getType())) {
            replayButton(block);
            sender.sendMessage(messages.get(sender, "redstone.button-activated"));
            audit(sender, recorded, block, "button activated");
            return true;
        }

        boolean powered = !powered(block);
        if (!setPowered(block, powered)) {
            sender.sendMessage(messages.get(sender, "redstone.last-control-unavailable"));
            return true;
        }
        sender.sendMessage(messages.get(sender, powered ? "redstone.lever-activated" : "redstone.lever-deactivated"));
        audit(sender, recorded, block, powered ? "lever activated" : "lever deactivated");
        return true;
    }

    private void replayButton(Block block) {
        BlockKey key = BlockKey.from(block);
        BukkitTask oldRelease = buttonReleaseTasks.remove(key);
        if (oldRelease != null) oldRelease.cancel();

        // Force a new rising edge even when the button is still powered.
        setPowered(block, false);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Block current = resolve(key, block.getType());
            if (current == null || !setPowered(current, true)) return;
            BukkitTask release = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Block releaseTarget = resolve(key, block.getType());
                if (releaseTarget != null) setPowered(releaseTarget, false);
                buttonReleaseTasks.remove(key);
            }, buttonPulseTicks(block.getType()));
            buttonReleaseTasks.put(key, release);
        });
    }

    private Block resolve(LastControl recorded) {
        return resolve(recorded.key(), recorded.type());
    }

    private static Block resolve(BlockKey key, Material expectedType) {
        World world = Bukkit.getWorld(key.worldId());
        if (world == null) return null;
        Block block = world.getBlockAt(key.x(), key.y(), key.z());
        return block.getType() == expectedType && isControl(block.getType()) ? block : null;
    }

    private static boolean isControl(Material material) {
        return material == Material.LEVER || isButton(material);
    }

    private static boolean isButton(Material material) {
        return material.name().endsWith("_BUTTON");
    }

    private static boolean powered(Block block) {
        BlockData data = block.getBlockData();
        return data instanceof Powerable powerable && powerable.isPowered();
    }

    private static boolean setPowered(Block block, boolean powered) {
        BlockData data = block.getBlockData();
        if (!(data instanceof Powerable powerable)) return false;
        powerable.setPowered(powered);
        block.setBlockData(powerable, true);
        return true;
    }

    private static long buttonPulseTicks(Material material) {
        return material == Material.STONE_BUTTON || material == Material.POLISHED_BLACKSTONE_BUTTON ? 20L : 30L;
    }

    private void audit(CommandSender sender, LastControl recorded, Block block, String action) {
        plugin.getLogger().info("Redstone replay: actor=" + sender.getName()
                + " action=" + action + " location=" + block.getWorld().getName() + ':'
                + block.getX() + ',' + block.getY() + ',' + block.getZ()
                + " lastUser=" + recorded.playerId());
    }

    private record LastControl(BlockKey key, Material type, UUID playerId) { }

    private record BlockKey(UUID worldId, int x, int y, int z) {
        static BlockKey from(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }
}
