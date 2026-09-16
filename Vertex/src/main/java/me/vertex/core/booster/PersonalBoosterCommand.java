package me.vertex.core.booster;

import me.vertex.core.enchant.listener.RuneEffectListener;
import me.vertex.core.lang.Messages;
import me.vertex.core.menu.MenuRegistry;
import me.vertex.core.storage.ItemGiver;
import me.vertex.core.util.CommandUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Command handler for {@code /xpbooster} and {@code /sellbooster}.
 * Allows players to check active status and admins to generate customized or randomized booster potions.
 */
public final class PersonalBoosterCommand implements CommandExecutor, TabCompleter {

    private final RuneEffectListener runeEffectListener;
    private final BoosterService boosterService;
    private final Messages messages;
    private final MenuRegistry menuRegistry;
    private final BoosterCategory category;
    private final boolean isExp;
    private final String typeLabel;
    private final String givePermNode;
    private final String usePermNode;

    public PersonalBoosterCommand(RuneEffectListener runeEffectListener, BoosterService boosterService,
                                  Messages messages, MenuRegistry menuRegistry, BoosterCategory category) {
        this.runeEffectListener = runeEffectListener;
        this.boosterService = boosterService;
        this.messages = messages;
        this.menuRegistry = menuRegistry;
        this.category = category;
        this.isExp = category == BoosterCategory.EXP;
        this.typeLabel = this.isExp ? "XP" : "Sell";
        this.givePermNode = "vertex." + (this.isExp ? "xp" : "sell") + "booster.give";
        this.usePermNode = "vertex." + (this.isExp ? "xp" : "sell") + "booster.use";
    }

    public PersonalBoosterCommand(RuneEffectListener runeEffectListener, BoosterService boosterService,
                                  Messages messages, BoosterCategory category) {
        this(runeEffectListener, boosterService, messages, null, category);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!hasUsePermission(sender)) {
                sender.sendMessage(messages.get(sender, "general.no-permission"));
                return true;
            }

            if (sender instanceof Player player) {
                RuneEffectListener.PersonalBoost active = runeEffectListener.activePersonalBoost(player.getUniqueId(), category);
                if (active != null) {
                    long remainingSeconds = Math.max(0, (active.expiresAt() - System.currentTimeMillis()) / 1000);
                    String durationStr = RuneEffectListener.formatDuration(remainingSeconds);
                    String percentFormatted = formatPercent(active.percent());
                    player.sendMessage(messages.get(player, "boosters.active-status",
                            "type", typeLabel,
                            "percent", percentFormatted,
                            "duration", durationStr));
                } else {
                    player.sendMessage(messages.get(player, "boosters.no-active",
                            "type", typeLabel));
                }

                if (menuRegistry != null) {
                    BoostersMenu.openDetail(player, player, boosterService, messages, menuRegistry, category);
                }
            }

            if (hasGivePermission(sender)) {
                sender.sendMessage(messages.get(sender, "boosters.usage-personal",
                        "label", label));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("give")) {
            if (!hasGivePermission(sender)) {
                sender.sendMessage(messages.get(sender, "general.no-permission"));
                return true;
            }

            Player target = null;
            int nextArg = 1;

            if (args.length >= 2) {
                target = Bukkit.getPlayer(args[1]);
                if (target != null) {
                    nextArg = 2;
                } else if (sender instanceof Player player) {
                    // Check if args[1] was actually a percentage (e.g. /xpbooster give 50)
                    try {
                        Double.parseDouble(args[1].replace("%", ""));
                        target = player;
                        nextArg = 1;
                    } catch (NumberFormatException ignored) {
                        sender.sendMessage(messages.get(sender, "general.player-not-found", "player", args[1]));
                        return true;
                    }
                } else {
                    sender.sendMessage(messages.get(sender, "general.player-not-found", "player", args[1]));
                    return true;
                }
            } else {
                if (sender instanceof Player player) {
                    target = player;
                } else {
                    sender.sendMessage(messages.get(sender, "boosters.console-specify-player",
                            "label", label));
                    return true;
                }
            }

            double percent;
            if (args.length > nextArg) {
                try {
                    percent = Double.parseDouble(args[nextArg].replace("%", ""));
                    if (percent <= 0) {
                        sender.sendMessage(messages.get(sender, "boosters.invalid-percent"));
                        return true;
                    }
                    nextArg++;
                } catch (NumberFormatException e) {
                    sender.sendMessage(messages.get(sender, "boosters.invalid-percent-format",
                            "value", args[nextArg]));
                    return true;
                }
            } else {
                // Random percentage
                if (isExp) {
                    int min = 15;
                    int max = 200;
                    int steps = (max - min) / 5;
                    percent = min + (ThreadLocalRandom.current().nextInt(steps + 1) * 5);
                } else {
                    percent = ThreadLocalRandom.current().nextInt(25, 125);
                }
            }

            double durationSeconds;
            if (args.length > nextArg) {
                String rawDuration = args[nextArg].toLowerCase(Locale.ROOT);
                try {
                    if (rawDuration.endsWith("s")) {
                        durationSeconds = Double.parseDouble(rawDuration.substring(0, rawDuration.length() - 1));
                    } else if (rawDuration.endsWith("m")) {
                        durationSeconds = Double.parseDouble(rawDuration.substring(0, rawDuration.length() - 1)) * 60;
                    } else if (rawDuration.endsWith("h")) {
                        durationSeconds = Double.parseDouble(rawDuration.substring(0, rawDuration.length() - 1)) * 3600;
                    } else {
                        durationSeconds = Double.parseDouble(rawDuration);
                    }
                    if (durationSeconds <= 0) {
                        sender.sendMessage(messages.get(sender, "boosters.invalid-duration"));
                        return true;
                    }
                } catch (NumberFormatException e) {
                    sender.sendMessage(messages.get(sender, "boosters.invalid-duration-format",
                            "value", args[nextArg]));
                    return true;
                }
            } else {
                // Random duration
                if (isExp) {
                    int[] expTimes = {300, 600, 900, 1200, 1500, 1800};
                    durationSeconds = expTimes[ThreadLocalRandom.current().nextInt(expTimes.length)];
                } else {
                    int[] sellTimes = {15, 30, 45, 60};
                    durationSeconds = sellTimes[ThreadLocalRandom.current().nextInt(sellTimes.length)];
                }
            }

            ItemStack potion = runeEffectListener.createBoosterPotion(category, percent, durationSeconds);
            ItemGiver.give(target, List.of(potion));

            String durationFormatted = RuneEffectListener.formatDuration(durationSeconds);
            String percentFormatted = formatPercent(percent);

            sender.sendMessage(messages.get(sender, "boosters.booster-given",
                    "player", target.getName(),
                    "type", typeLabel,
                    "percent", percentFormatted,
                    "duration", durationFormatted));

            if (!target.equals(sender)) {
                target.sendMessage(messages.get(target, "boosters.booster-received",
                        "type", typeLabel,
                        "percent", percentFormatted,
                        "duration", durationFormatted));
            }
            return true;
        }

        sender.sendMessage(messages.get(sender, "boosters.usage-personal", "label", label));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!hasGivePermission(sender)) {
            return List.of();
        }
        if (args.length == 1) {
            return CommandUtil.filterPrefix(List.of("give"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return CommandUtil.matchOnlinePlayers(args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            List<String> suggestions = isExp
                    ? List.of("25", "50", "75", "100", "150", "200")
                    : List.of("25", "50", "75", "100", "124");
            return CommandUtil.filterPrefix(suggestions, args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("give")) {
            List<String> suggestions = isExp
                    ? List.of("300", "600", "900", "1200", "1800")
                    : List.of("15", "30", "45", "60");
            return CommandUtil.filterPrefix(suggestions, args[3]);
        }
        return List.of();
    }

    private boolean hasUsePermission(CommandSender sender) {
        return sender.hasPermission(usePermNode)
                || sender.hasPermission("vertex.booster.use")
                || sender.isOp();
    }

    private boolean hasGivePermission(CommandSender sender) {
        return sender.hasPermission(givePermNode)
                || sender.hasPermission("vertex.booster.give")
                || sender.isOp();
    }

    private static String formatPercent(double percent) {
        return percent == (long) percent
                ? String.format(Locale.ROOT, "%d", (long) percent)
                : String.format(Locale.ROOT, "%.1f", percent);
    }
}
