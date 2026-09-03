package me.hcfcore.core;

import me.hcfcore.core.ability.AbilitiesCommand;
import me.hcfcore.core.ability.AbilityManager;
import me.hcfcore.core.ability.AbilityMenuListener;
import me.hcfcore.core.ability.CooldownsCommand;
import me.hcfcore.core.ability.AntiBlockupBoneListener;
import me.hcfcore.core.ability.FakePearlListener;
import me.hcfcore.core.ability.GetItemCommand;
import me.hcfcore.core.ability.GrapplingHookListener;
import me.hcfcore.core.ability.LeapListener;
import me.hcfcore.core.ability.MageSpellListener;
import me.hcfcore.core.ability.PortableBardListener;
import me.hcfcore.core.ability.RepairListener;
import me.hcfcore.core.ability.RogueBackstabListener;
import me.hcfcore.core.ability.SwitcherSnowballListener;
import me.hcfcore.core.ability.TimeWarpPearlListener;
import me.hcfcore.core.ability.VanillaCooldownListener;
import me.hcfcore.core.ability.VanillaCooldownManager;
import me.hcfcore.core.chat.ChatFormatterListener;
import me.hcfcore.core.ability.PearlStunnerListener;
import me.hcfcore.core.ability.RabbitsFeedListener;
import me.hcfcore.core.factions.FactionCommandListener;
import me.hcfcore.core.kit.KitCommand;
import me.hcfcore.core.kit.KitManager;
import me.hcfcore.core.kit.KitMenuListener;
import me.hcfcore.core.kit.KitsCommand;
import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.lang.LanguageCommand;
import me.hcfcore.core.listener.CombatListener;
import me.hcfcore.core.listener.PlayerConnectionListener;
import me.hcfcore.core.pvp.CombatCheckCommand;
import me.hcfcore.core.pvp.ArcherTagListener;
import me.hcfcore.core.pvp.ArcherTagManager;
import me.hcfcore.core.pvp.CombatManager;
import me.hcfcore.core.pvp.CombatTagCommand;
import me.hcfcore.core.pvp.UncombatCommand;
import me.hcfcore.core.pvp.LegacyCombatManager;
import me.hcfcore.core.reboot.NextRebootCommand;
import me.hcfcore.core.reboot.RebootCommand;
import me.hcfcore.core.reboot.RebootManager;
import me.hcfcore.core.scoreboard.ScoreboardManager;
import me.hcfcore.core.faction.RallyListener;
import me.hcfcore.core.faction.RallyManager;
import me.hcfcore.core.staff.DeathListener;
import me.hcfcore.core.staff.DeathManager;
import me.hcfcore.core.staff.InvRestoreMenuListener;
import me.hcfcore.core.staff.RollbackCommand;
import me.hcfcore.core.storage.Database;
import me.hcfcore.core.storage.MySQLStorage;
import me.hcfcore.core.storage.Storage;
import me.hcfcore.core.user.UserManager;
import me.hcfcore.core.tag.TagManager;
import me.hcfcore.core.tag.TagsCommand;
import me.hcfcore.core.tag.TagMenuListener;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.logging.Level;

public final class HCFCorePlugin extends JavaPlugin {

    private Database database;
    private Storage storage;
    private UserManager userManager;
    private Messages messages;
    private LanguageCommand languageCommand;
    private KitManager kitManager;
    private AbilityManager abilityManager;
    private ScoreboardManager scoreboardManager;
    private CombatManager combatManager;
    private LegacyCombatManager legacyCombatManager;
    private RebootManager rebootManager;
    private PlayerConnectionListener playerConnectionListener;
    private RepairListener repairListener;
    private TagManager tagManager;
    private VanillaCooldownManager vanillaCooldownManager;
    private ArcherTagManager archerTagManager;
    private DeathManager deathManager;
    private RallyManager rallyManager;
    private RallyListener rallyListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (!validateRuntimeDependencies()) {
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        try {
            database = new Database(getConfig());
            storage = new MySQLStorage(database);
            // One-time schema check at boot; the recurring load/save calls
            // made during gameplay are the ones that must stay off the main
            // thread, and they do (see UserManager/KitManager).
            storage.init();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to initialize the database, disabling.", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        userManager = new UserManager(this, storage);
        messages = new Messages(this, userManager);
        messages.load();
        kitManager = new KitManager(this, storage, userManager, messages);
        kitManager.load();
        kitManager.start();
        abilityManager = new AbilityManager(this, storage);
        abilityManager.load();
        vanillaCooldownManager = new VanillaCooldownManager();
        archerTagManager = new ArcherTagManager();
        tagManager = new TagManager(this);
        tagManager.load();

        scoreboardManager = new ScoreboardManager(this, getConfig(), userManager, abilityManager);
        scoreboardManager.start();

        combatManager = new CombatManager(
                this,
                messages,
                getConfig().getInt("pvp.combat-tag-seconds", 30),
                getConfig().getBoolean("pvp.logout-penalty", true),
                getConfig().getInt("pvp.actionbar-update-interval-ticks", 4));
        combatManager.start();

        legacyCombatManager = new LegacyCombatManager(this);
        Bukkit.getPluginManager().registerEvents(legacyCombatManager, this);
        legacyCombatManager.start();

        rebootManager = new RebootManager(this, messages);
        rebootManager.start();

        deathManager = new DeathManager(this, storage);
        Bukkit.getPluginManager().registerEvents(new DeathListener(deathManager), this);
        Bukkit.getPluginManager().registerEvents(new InvRestoreMenuListener(this, deathManager), this);

        rallyManager = new RallyManager(this, messages);
        rallyListener = new RallyListener(this, rallyManager);
        Bukkit.getPluginManager().registerEvents(rallyListener, this);

        Bukkit.getPluginManager().registerEvents(new CombatListener(combatManager), this);
        playerConnectionListener = new PlayerConnectionListener(userManager, scoreboardManager, combatManager);
        Bukkit.getPluginManager().registerEvents(playerConnectionListener, this);
        Bukkit.getPluginManager().registerEvents(new AbilityMenuListener(this, abilityManager, messages), this);
        Bukkit.getPluginManager().registerEvents(
                new AntiBlockupBoneListener(this, abilityManager, userManager, messages), this);
        Bukkit.getPluginManager().registerEvents(
            new MageSpellListener(this, abilityManager, userManager, messages), this);
        Bukkit.getPluginManager().registerEvents(
            new RogueBackstabListener(this, abilityManager, userManager, messages), this);
        Bukkit.getPluginManager().registerEvents(
                new FakePearlListener(this, abilityManager, userManager, messages), this);
        Bukkit.getPluginManager().registerEvents(
                new GrapplingHookListener(this, abilityManager, userManager, messages), this);
        Bukkit.getPluginManager().registerEvents(new LeapListener(this, abilityManager, userManager, messages), this);
        Bukkit.getPluginManager().registerEvents(
                new PortableBardListener(this, abilityManager, userManager, messages), this);
        repairListener = new RepairListener(this, abilityManager, userManager, scoreboardManager, messages);
        Bukkit.getPluginManager().registerEvents(repairListener, this);
        Bukkit.getPluginManager().registerEvents(
                new SwitcherSnowballListener(this, abilityManager, userManager, messages), this);
        Bukkit.getPluginManager().registerEvents(
                new TimeWarpPearlListener(this, abilityManager, userManager, messages), this);
        Bukkit.getPluginManager().registerEvents(new VanillaCooldownListener(this, messages, vanillaCooldownManager), this);
        Bukkit.getPluginManager().registerEvents(new ArcherTagListener(this, archerTagManager, messages), this);
        Bukkit.getPluginManager().registerEvents(new PearlStunnerListener(this, abilityManager, userManager, messages), this);
        Bukkit.getPluginManager().registerEvents(new RabbitsFeedListener(this, abilityManager, userManager, messages), this);
        Bukkit.getPluginManager().registerEvents(new TagMenuListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ChatFormatterListener(tagManager, getConfig()), this);
        Bukkit.getPluginManager().registerEvents(new FactionCommandListener(this, messages), this);

        KitCommand kitCommand = new KitCommand(this, kitManager, messages);
        getCommand("kit").setExecutor(kitCommand);
        getCommand("kit").setTabCompleter(kitCommand);
        getCommand("kits").setExecutor(new KitsCommand(this, kitManager, userManager, messages));
        Bukkit.getPluginManager().registerEvents(new KitMenuListener(this, kitManager, messages), this);

        getCommand("hcfcore").setExecutor(new HCFCoreCommand(this, messages));

        languageCommand = new LanguageCommand(this, storage, userManager, messages);
        getCommand("language").setExecutor(languageCommand);
        getCommand("language").setTabCompleter(languageCommand);
        getCommand("cooldowns").setExecutor(new CooldownsCommand(
                kitManager, abilityManager, userManager, messages, vanillaCooldownManager));
        getCommand("tags").setExecutor(new TagsCommand(tagManager, messages));

        UncombatCommand uncombatCommand = new UncombatCommand(combatManager, messages);
        getCommand("uncombat").setExecutor(uncombatCommand);
        getCommand("uncombat").setTabCompleter(uncombatCommand);

        CombatCheckCommand combatCheckCommand = new CombatCheckCommand(combatManager, messages);
        getCommand("combatcheck").setExecutor(combatCheckCommand);
        getCommand("combatcheck").setTabCompleter(combatCheckCommand);

        CombatTagCommand combatTagCommand = new CombatTagCommand(combatManager, messages);
        getCommand("combattag").setExecutor(combatTagCommand);
        getCommand("combattag").setTabCompleter(combatTagCommand);

        GetItemCommand getItemCommand = new GetItemCommand(this, abilityManager, messages);
        getCommand("getitem").setExecutor(getItemCommand);
        getCommand("getitem").setTabCompleter(getItemCommand);
        getCommand("abilities").setExecutor(new AbilitiesCommand(this, abilityManager, messages));

        getCommand("reboot").setExecutor(new RebootCommand(rebootManager, messages));
        getCommand("nextreboot").setExecutor(new NextRebootCommand(rebootManager));

        RollbackCommand rollbackCommand = new RollbackCommand(this, deathManager, messages);
        getCommand("rollback").setExecutor(rollbackCommand);
        getCommand("rollback").setTabCompleter(rollbackCommand);

        for (var player : Bukkit.getOnlinePlayers()) {
            var uuid = player.getUniqueId();
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> userManager.load(uuid));
            scoreboardManager.setup(player);
        }
    }

    @Override
    public void onDisable() {
        if (scoreboardManager != null) {
            scoreboardManager.stop();
        }
        if (combatManager != null) {
            combatManager.stop();
        }
        if (rebootManager != null) {
            rebootManager.stop();
        }
        if (kitManager != null) {
            kitManager.shutdown();
        }
        if (abilityManager != null) {
            abilityManager.awaitWrites();
        }
        if (languageCommand != null) {
            languageCommand.awaitWrites();
        }
        if (rallyListener != null) {
            rallyListener.shutdown();
        }
        if (rallyManager != null) {
            rallyManager.shutdown();
        }
        if (storage != null) {
            storage.close();
        }
    }

    public static boolean hasRequiredDependency(PluginManager pluginManager) {
        Plugin factions = pluginManager.getPlugin("FactionsUUID");
        return factions != null;
    }

    public boolean validateRuntimeDependencies() {
        if (!hasRequiredDependency(Bukkit.getPluginManager())) {
            getLogger().severe("Missing required dependency: FactionsUUID. Disabling HCFCore.");
            return false;
        }

        List<String> optionalDependencies = List.of("Vault", "WorldGuard", "LuckPerms");
        for (String dependency : optionalDependencies) {
            if (Bukkit.getPluginManager().getPlugin(dependency) == null) {
                getLogger().warning("Optional dependency not detected: " + dependency + ". Related features will be disabled.");
            }
        }

        return true;
    }

    public void reload() {
        reloadConfig();

        if (messages != null) {
            messages.load();
        }
        if (kitManager != null) {
            kitManager.load();
        }
        if (abilityManager != null) {
            abilityManager.load();
        }
        if (tagManager != null) {
            tagManager.load();
        }

        if (scoreboardManager != null) {
            scoreboardManager.stop();
            scoreboardManager = null;
        }
        if (userManager != null && abilityManager != null) {
            scoreboardManager = new ScoreboardManager(this, getConfig(), userManager, abilityManager);
            scoreboardManager.start();
        }

        if (playerConnectionListener != null) {
            playerConnectionListener.setScoreboardManager(scoreboardManager);
        }
        if (repairListener != null) {
            repairListener.setScoreboardManager(scoreboardManager);
        }
        if (scoreboardManager != null) {
            for (var player : Bukkit.getOnlinePlayers()) {
                scoreboardManager.setup(player);
            }
        }

        if (combatManager != null) {
            combatManager.reconfigure(
                    getConfig().getInt("pvp.combat-tag-seconds", 30),
                    getConfig().getBoolean("pvp.logout-penalty", true),
                    getConfig().getInt("pvp.actionbar-update-interval-ticks", 4));
        }
        if (legacyCombatManager != null) {
            legacyCombatManager.reconfigure();
        }
        if (rebootManager != null) {
            rebootManager.reconfigure();
        }
    }
}
