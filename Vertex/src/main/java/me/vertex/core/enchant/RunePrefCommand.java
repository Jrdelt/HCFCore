package me.vertex.core.enchant;

import me.vertex.core.lang.Messages;
import me.vertex.core.preferences.AnnouncementPreferenceManager;
import me.vertex.core.preferences.SettingsMenu;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * {@code /runepref}: no-arg opens {@link SettingsMenu} (already showing the
 * global rune message/sound/particle toggles); {@code volume <0-100>} sets
 * the global rune sound volume; {@code rune <id> <message|cooldown-message|
 * sound|particle> <on|off>} sets a per-rune override; {@code rune <id>
 * reset} clears every override for that rune's settings.
 */
public final class RunePrefCommand implements CommandExecutor {

    private final EnchantManager manager;
    private final AnnouncementPreferenceManager announcementPreferences;
    private final RunePreferenceManager runePreferences;
    private final AutoIncinerationNotifier autoNotifier;
    private final Messages messages;

    public RunePrefCommand(EnchantManager manager, AnnouncementPreferenceManager announcementPreferences,
            RunePreferenceManager runePreferences, AutoIncinerationNotifier autoNotifier, Messages messages) {
        this.manager = manager;
        this.announcementPreferences = announcementPreferences;
        this.runePreferences = runePreferences;
        this.autoNotifier = autoNotifier;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get(sender, "general.players-only"));
            return true;
        }
        if (args.length == 0) {
            SettingsMenu.open(player, announcementPreferences, messages);
            return true;
        }
        if (args[0].equalsIgnoreCase("volume")) {
            return handleVolume(player, args);
        }
        if (args[0].equalsIgnoreCase("rune")) {
            return handleRune(player, args);
        }
        if (args[0].equalsIgnoreCase("notify")) {
            return handleNotify(player, args);
        }
        player.sendMessage(messages.get(player, "rune.pref-usage"));
        return true;
    }

    private boolean handleNotify(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(messages.get(player, "rune.pref-usage"));
            return true;
        }
        AutoIncinerationNotifier.Mode mode;
        try {
            mode = AutoIncinerationNotifier.Mode.valueOf(args[1].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            player.sendMessage(messages.get(player, "rune.pref-usage"));
            return true;
        }
        autoNotifier.setMode(player.getUniqueId(), mode);
        player.sendMessage(messages.get(player, "rune.pref-notify-set", "mode", mode.name()));
        return true;
    }

    private boolean handleVolume(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(messages.get(player, "rune.pref-usage"));
            return true;
        }
        int volume;
        try {
            volume = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(messages.get(player, "rune.pref-usage"));
            return true;
        }
        volume = Math.max(0, Math.min(100, volume));
        runePreferences.set(player.getUniqueId(), RunePreferenceManager.GLOBAL, RunePreferenceManager.KEY_VOLUME,
                String.valueOf(volume));
        player.sendMessage(messages.get(player, "rune.pref-volume-set", "volume", String.valueOf(volume)));
        return true;
    }

    private boolean handleRune(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(messages.get(player, "rune.pref-usage"));
            return true;
        }
        String runeId = args[1].toLowerCase(Locale.ROOT);
        if (manager.definition(runeId) == null) {
            player.sendMessage(messages.get(player, "rune.pref-unknown-rune", "rune", runeId));
            return true;
        }
        if (args[2].equalsIgnoreCase("reset")) {
            for (String key : new String[] {RunePreferenceManager.KEY_MESSAGE, RunePreferenceManager.KEY_COOLDOWN_MESSAGE,
                    RunePreferenceManager.KEY_SOUND, RunePreferenceManager.KEY_PARTICLE}) {
                runePreferences.reset(player.getUniqueId(), runeId, key);
            }
            player.sendMessage(messages.get(player, "rune.pref-rune-reset", "rune", runeId));
            return true;
        }
        String settingKey = switch (args[2].toLowerCase(Locale.ROOT)) {
            case "message" -> RunePreferenceManager.KEY_MESSAGE;
            case "cooldown-message" -> RunePreferenceManager.KEY_COOLDOWN_MESSAGE;
            case "sound" -> RunePreferenceManager.KEY_SOUND;
            case "particle" -> RunePreferenceManager.KEY_PARTICLE;
            default -> null;
        };
        if (settingKey == null || args.length < 4) {
            player.sendMessage(messages.get(player, "rune.pref-usage"));
            return true;
        }
        boolean on = args[3].equalsIgnoreCase("on");
        if (!on && !args[3].equalsIgnoreCase("off")) {
            player.sendMessage(messages.get(player, "rune.pref-usage"));
            return true;
        }
        runePreferences.set(player.getUniqueId(), runeId, settingKey, String.valueOf(on));
        player.sendMessage(messages.get(player, "rune.pref-rune-set", "rune", runeId, "setting", args[2].toLowerCase(Locale.ROOT),
                "state", on ? "on" : "off"));
        return true;
    }
}
