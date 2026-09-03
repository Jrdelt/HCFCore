package me.hcfcore.core.reboot;

import me.hcfcore.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

public final class RebootManager {

    private final Plugin plugin;
    private final Messages messages;
    private final Set<Integer> sentReminders = new HashSet<>();
    private int defaultDelayMinutes;
    private List<Integer> reminderMinutes;
    private long rebootAt;
    private int taskId = -1;

    public RebootManager(Plugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
        reconfigure();
    }

    public void start() {
        stopTask();
        taskId = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L).getTaskId();
    }

    public void stop() {
        stopTask();
    }

    public void reconfigure() {
        defaultDelayMinutes = Math.max(1, plugin.getConfig().getInt("reboot.default-delay-minutes", 10));
        reminderMinutes = plugin.getConfig().getIntegerList("reboot.reminder-minutes").stream()
                .filter(minutes -> minutes > 0)
                .distinct()
                .sorted()
                .toList();
    }

    public boolean schedule() {
        return schedule(defaultDelayMinutes);
    }

    public boolean schedule(int delayMinutes) {
        if (rebootAt > System.currentTimeMillis()) {
            return false;
        }
        rebootAt = System.currentTimeMillis() + (long) Math.max(1, delayMinutes) * 60_000L;
        sentReminders.clear();
        broadcast("reboot.started", "minutes", String.valueOf(Math.max(1, delayMinutes)));
        return true;
    }

    public boolean cancel() {
        if (rebootAt <= System.currentTimeMillis()) {
            return false;
        }
        rebootAt = 0;
        sentReminders.clear();
        broadcast("reboot.cancelled");
        return true;
    }

    public boolean isScheduled() {
        return rebootAt > 0;
    }

    public long getRemainingSeconds() {
        if (!isScheduled()) {
            return -1;
        }
        return Math.max(0, (rebootAt - System.currentTimeMillis() + 999) / 1000);
    }

    public void sendNextReboot(org.bukkit.command.CommandSender sender) {
        long remainingSeconds = getRemainingSeconds();
        if (remainingSeconds < 0) {
            sender.sendMessage(messages.get(sender, "reboot.none"));
            return;
        }
        sender.sendMessage(messages.get(sender, "reboot.next", durationPlaceholders(remainingSeconds)));
    }

    private void tick() {
        if (!isScheduled()) {
            return;
        }

        long remainingSeconds = getRemainingSeconds();
        if (remainingSeconds <= 0) {
            broadcast("reboot.now");
            plugin.getLogger().log(Level.INFO, "Reboot countdown reached zero; shutting down server.");
            Bukkit.shutdown();
            rebootAt = 0;
            return;
        }

        for (int minutes : reminderMinutes) {
            if (remainingSeconds <= minutes * 60L && sentReminders.add(minutes)) {
                broadcastDuration("reboot.reminder", remainingSeconds);
            }
        }
    }

    private void broadcast(String key, String... placeholders) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(messages.get(player, key, placeholders));
        }
        Bukkit.getConsoleSender().sendMessage(messages.get(Bukkit.getConsoleSender(), key, placeholders));
    }

    private void broadcastDuration(String key, long seconds) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(messages.get(player, key, durationPlaceholders(seconds)));
        }
        Bukkit.getConsoleSender().sendMessage(messages.get(Bukkit.getConsoleSender(), key,
                durationPlaceholders(seconds)));
    }

    private String[] durationPlaceholders(long seconds) {
        long minutes = seconds / 60;
        long remainingSeconds = seconds % 60;
        return new String[]{"minutes", String.valueOf(minutes), "seconds", String.valueOf(remainingSeconds)};
    }

    private void stopTask() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }
}