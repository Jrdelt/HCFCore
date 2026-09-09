package me.vertex.core.gc;

import me.vertex.core.util.Numbers;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the one shared, admin-configured sign the whole server uses to type a
 * GC deposit or withdraw amount.
 *
 * <p>This is a genuinely new kind of interaction surface for this codebase
 * -- there is no {@code SignChangeEvent} precedent anywhere else in it. An
 * anvil was rejected elsewhere for quantity prompts (see {@code
 * ChatAmountPrompt}'s class doc: a client-side container-reset race that
 * survived several rounds of workarounds), and chat capture doesn't fit
 * this one flow the way the plan calls for a sign. A sign has the same
 * "either the server sees the submitted value or it doesn't" property chat
 * does, with one caveat this class exists to cover: pressing Escape on a
 * sign-edit screen fires no event at all in vanilla, so a player who opens
 * the sign and backs out leaves the block converted and the lock held until
 * something else releases it -- the timeout task below is that something.
 *
 * <p>Only one player may use the shared sign at a time -- a lock, not a
 * queue; a second player is simply told to try again shortly. The block at
 * the configured location is converted to a sign only for the duration of
 * one prompt, then restored to exactly what it was before, so the location
 * can be an entirely ordinary block (a sign, a wool block, air -- whatever
 * an admin already had there) the rest of the time.
 */
public final class GcSignPrompt {

    public enum Operation {
        DEPOSIT, WITHDRAW
    }

    public enum RequestResult {
        OK, NOT_CONFIGURED, BUSY
    }

    private final Plugin plugin;
    private final File configFile;

    private volatile Location signLocation;
    private final Object lock = new Object();
    private Pending pending;

    public GcSignPrompt(Plugin plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "gc.yml");
    }

    /** Reads {@code sign-input.*} from {@code gc.yml}. Called at startup and on {@code /vertex reload}. */
    public void loadLocation() {
        if (!configFile.exists()) {
            signLocation = null;
            return;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        String worldName = config.getString("sign-input.world", "");
        if (worldName == null || worldName.isBlank()) {
            signLocation = null;
            return;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("GC sign-input world '" + worldName
                    + "' is not currently loaded; the shared deposit/withdraw sign is unavailable until it is.");
            signLocation = null;
            return;
        }
        signLocation = new Location(world, config.getInt("sign-input.x"), config.getInt("sign-input.y"),
                config.getInt("sign-input.z"));
    }

    public boolean isConfigured() {
        return signLocation != null;
    }

    public Location location() {
        return signLocation;
    }

    private static final Pattern SIGN_INPUT_KEY_LINE =
            Pattern.compile("^(\\s+)(world|x|y|z):\\s*[^#]*?(\\s*)(#.*)?$");

    /**
     * Persists the shared sign-input block location to {@code gc.yml} and
     * starts using it immediately. A targeted line edit, not {@code
     * FileConfiguration#save} -- Bukkit's own YAML writer drops every
     * comment in the file, and gc.yml's comments are most of its
     * documentation (the same reasoning {@code StorageMigrator
     * #writeStorageType} already applies to config.yml).
     */
    public void setLocation(Location location) {
        Location blockLocation = location.getBlock().getLocation();
        signLocation = blockLocation;
        if (!configFile.exists()) {
            plugin.saveResource("gc.yml", false);
        }
        try {
            java.util.Map<String, String> values = java.util.Map.of(
                    "world", blockLocation.getWorld().getName(),
                    "x", String.valueOf(blockLocation.getBlockX()),
                    "y", String.valueOf(blockLocation.getBlockY()),
                    "z", String.valueOf(blockLocation.getBlockZ()));
            List<String> lines = new ArrayList<>(Files.readAllLines(configFile.toPath(), StandardCharsets.UTF_8));
            boolean inSection = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String withoutComment = line.split("#", 2)[0];
                if (inSection) {
                    if (!withoutComment.isBlank() && !Character.isWhitespace(line.charAt(0))) {
                        break;
                    }
                    Matcher matcher = SIGN_INPUT_KEY_LINE.matcher(line);
                    if (matcher.matches()) {
                        String replacement = "world".equals(matcher.group(2))
                                ? "\"" + values.get(matcher.group(2)) + "\"" : values.get(matcher.group(2));
                        lines.set(i, matcher.group(1) + matcher.group(2) + ": " + replacement
                                + matcher.group(3) + (matcher.group(4) == null ? "" : matcher.group(4)));
                    }
                } else if (withoutComment.stripTrailing().equals("sign-input:")) {
                    inSection = true;
                }
            }
            Files.write(configFile.toPath(), lines, StandardCharsets.UTF_8);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to persist the GC sign-input location to gc.yml.", e);
        }
    }

    /**
     * @param timeoutSeconds force-restores the block and releases the lock
     *                        if nobody finishes within this many seconds.
     * @param onAmount        run on the main thread with the parsed, positive amount.
     * @param onCancel        run (also on the main thread) if the sign times
     *                        out, the player disconnects mid-edit, or an
     *                        invalid line was entered. Never run once
     *                        {@code onAmount} already ran.
     */
    public RequestResult request(Player player, Operation operation, int timeoutSeconds, Consumer<Long> onAmount,
            Runnable onCancel) {
        Location targetLocation = signLocation;
        if (targetLocation == null) {
            return RequestResult.NOT_CONFIGURED;
        }
        synchronized (lock) {
            if (pending != null) {
                return RequestResult.BUSY;
            }
            Block block = targetLocation.getBlock();
            BlockState original = block.getState();
            block.setType(Material.OAK_SIGN, false);
            BlockState converted = block.getState();
            if (!(converted instanceof Sign sign)) {
                // Should be unreachable -- setType(OAK_SIGN) always yields a
                // Sign state -- but never leave a lock behind if it somehow isn't.
                original.update(true, false);
                return RequestResult.NOT_CONFIGURED;
            }
            sign.setEditable(true);
            sign.setAllowedEditorUniqueId(player.getUniqueId());
            sign.update(true, false);

            int timeoutTicks = Math.max(1, timeoutSeconds) * 20;
            BukkitTask timeoutTask = Bukkit.getScheduler().runTaskLater(plugin,
                    () -> forceCancel(player.getUniqueId()), timeoutTicks);
            pending = new Pending(player.getUniqueId(), operation, original, onAmount, onCancel, timeoutTask);
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                forceCancel(player.getUniqueId());
                return;
            }
            BlockState current = targetLocation.getBlock().getState();
            if (current instanceof Sign sign) {
                player.openSign(sign, Side.FRONT);
            }
        });
        return RequestResult.OK;
    }

    /** The operation the currently pending prompt is for, or null if nobody is using the sign. */
    public Operation pendingOperation(UUID uuid) {
        synchronized (lock) {
            return pending != null && pending.playerUuid.equals(uuid) ? pending.operation : null;
        }
    }

    /**
     * Called by {@link GcSignListener} for every {@code SignChangeEvent}.
     *
     * @return true if this event belonged to the shared sign's active
     *         prompt -- the caller must cancel the event either way once
     *         this returns true, whether or not the typed line parsed.
     */
    public boolean handleSignChange(Player player, Location changedLocation, String rawLine) {
        Pending current;
        synchronized (lock) {
            if (pending == null || !pending.playerUuid.equals(player.getUniqueId())
                    || !sameBlock(signLocation, changedLocation)) {
                return false;
            }
            current = pending;
            pending = null;
        }
        current.timeoutTask.cancel();
        restore(current.original);

        Long amount = Numbers.parseLongPositive(rawLine == null ? "" : rawLine.trim());
        if (amount == null) {
            player.sendMessage(Component.text("That isn't a valid amount.", NamedTextColor.RED));
            Bukkit.getScheduler().runTask(plugin, current.onCancel);
            return true;
        }
        Bukkit.getScheduler().runTask(plugin, () -> current.onAmount.accept(amount));
        return true;
    }

    /** Force-restores the block and releases the lock -- a timeout, or the editing player disconnecting. */
    public void forceCancel(UUID uuid) {
        Pending current;
        synchronized (lock) {
            if (pending == null || !pending.playerUuid.equals(uuid)) {
                return;
            }
            current = pending;
            pending = null;
        }
        current.timeoutTask.cancel();
        restore(current.original);
        Bukkit.getScheduler().runTask(plugin, current.onCancel);
    }

    private void restore(BlockState original) {
        original.update(true, false);
    }

    private static boolean sameBlock(Location a, Location b) {
        return a != null && b != null && a.getWorld() != null && a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }

    private record Pending(UUID playerUuid, Operation operation, BlockState original, Consumer<Long> onAmount,
            Runnable onCancel, BukkitTask timeoutTask) {
    }
}
