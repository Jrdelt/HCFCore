package me.hcfcore.core;

import me.hcfcore.core.ability.AbilitiesCommand;
import me.hcfcore.core.ability.AbilityManager;
import me.hcfcore.core.ability.AbilityMenuListener;
import me.hcfcore.core.ability.AntiBlockupBoneListener;
import me.hcfcore.core.ability.FakePearlListener;
import me.hcfcore.core.ability.GetItemCommand;
import me.hcfcore.core.ability.GrapplingHookListener;
import me.hcfcore.core.ability.LeapListener;
import me.hcfcore.core.ability.PortableBardListener;
import me.hcfcore.core.ability.RepairListener;
import me.hcfcore.core.ability.SwitcherSnowballListener;
import me.hcfcore.core.ability.TimeWarpPearlListener;
import me.hcfcore.core.kit.KitCommand;
import me.hcfcore.core.kit.KitManager;
import me.hcfcore.core.kit.KitMenuListener;
import me.hcfcore.core.kit.KitsCommand;
import me.hcfcore.core.lang.LanguageCommand;
import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.listener.CombatListener;
import me.hcfcore.core.listener.PlayerConnectionListener;
import me.hcfcore.core.pvp.CombatCheckCommand;
import me.hcfcore.core.pvp.CombatManager;
import me.hcfcore.core.pvp.CombatTagCommand;
import me.hcfcore.core.pvp.UncombatCommand;
import me.hcfcore.core.scoreboard.ScoreboardManager;
import me.hcfcore.core.storage.Database;
import me.hcfcore.core.storage.MySQLStorage;
import me.hcfcore.core.storage.Storage;
import me.hcfcore.core.user.UserManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class HCFCorePlugin extends JavaPlugin {

    private Database database;
    private Storage storage;
    private UserManager userManager;
    private Messages messages;
    private KitManager kitManager;
    private AbilityManager abilityManager;
    private ScoreboardManager scoreboardManager;
    private CombatManager combatManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        database = new Database(getConfig());
        storage = new MySQLStorage(database);
        try {
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
        abilityManager = new AbilityManager(this, storage);
        abilityManager.load();

        scoreboardManager = new ScoreboardManager(this, getConfig());
        scoreboardManager.start();

        combatManager = new CombatManager(
                this,
                messages,
                getConfig().getInt("pvp.combat-tag-seconds", 15),
                getConfig().getBoolean("pvp.logout-penalty", true),
                getConfig().getInt("pvp.actionbar-update-interval-ticks", 4));
        combatManager.start();

        Bukkit.getPluginManager().registerEvents(new CombatListener(combatManager), this);
        Bukkit.getPluginManager().registerEvents(
                new PlayerConnectionListener(userManager, scoreboardManager, combatManager), this);
        Bukkit.getPluginManager().registerEvents(new AbilityMenuListener(this, abilityManager, messages), this);
        Bukkit.getPluginManager().registerEvents(
                new AntiBlockupBoneListener(this, abilityManager, userManager, messages), this);
        Bukkit.getPluginManager().registerEvents(
                new FakePearlListener(this, abilityManager, userManager, messages), this);
        Bukkit.getPluginManager().registerEvents(
                new GrapplingHookListener(this, abilityManager, userManager, messages), this);
        Bukkit.getPluginManager().registerEvents(new LeapListener(this, abilityManager, userManager, messages), this);
        Bukkit.getPluginManager().registerEvents(
                new PortableBardListener(this, abilityManager, userManager, messages), this);
        Bukkit.getPluginManager().registerEvents(
                new RepairListener(this, abilityManager, userManager, scoreboardManager, messages), this);
        Bukkit.getPluginManager().registerEvents(
                new SwitcherSnowballListener(this, abilityManager, userManager, messages), this);
        Bukkit.getPluginManager().registerEvents(
                new TimeWarpPearlListener(this, abilityManager, userManager, messages), this);

        KitCommand kitCommand = new KitCommand(kitManager, messages);
        getCommand("kit").setExecutor(kitCommand);
        getCommand("kit").setTabCompleter(kitCommand);
        getCommand("kits").setExecutor(new KitsCommand(this, kitManager, userManager, messages));
        Bukkit.getPluginManager().registerEvents(new KitMenuListener(this, kitManager, messages), this);

        getCommand("hcfcore").setExecutor(new HCFCoreCommand(this, messages));

        LanguageCommand languageCommand = new LanguageCommand(this, storage, userManager, messages);
        getCommand("language").setExecutor(languageCommand);
        getCommand("language").setTabCompleter(languageCommand);

        UncombatCommand uncombatCommand = new UncombatCommand(combatManager, messages);
        getCommand("uncombat").setExecutor(uncombatCommand);
        getCommand("uncombat").setTabCompleter(uncombatCommand);

        CombatCheckCommand combatCheckCommand = new CombatCheckCommand(combatManager, messages);
        getCommand("combatcheck").setExecutor(combatCheckCommand);
        getCommand("combatcheck").setTabCompleter(combatCheckCommand);

        CombatTagCommand combatTagCommand = new CombatTagCommand(combatManager, messages);
        getCommand("combattag").setExecutor(combatTagCommand);
        getCommand("combattag").setTabCompleter(combatTagCommand);

        GetItemCommand getItemCommand = new GetItemCommand(abilityManager, messages);
        getCommand("getitem").setExecutor(getItemCommand);
        getCommand("getitem").setTabCompleter(getItemCommand);
        getCommand("abilities").setExecutor(new AbilitiesCommand(this, abilityManager, messages));

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
        if (kitManager != null) {
            kitManager.shutdown();
        }
        if (storage != null) {
            storage.close();
        }
    }

    public void reload() {
        reloadConfig();
        messages.load();
        kitManager.load();
        abilityManager.load();
        if (scoreboardManager != null) {
            scoreboardManager.stop();
        }
        scoreboardManager = new ScoreboardManager(this, getConfig());
        scoreboardManager.start();
        for (var player : Bukkit.getOnlinePlayers()) {
            scoreboardManager.setup(player);
        }

        combatManager.reconfigure(
                getConfig().getInt("pvp.combat-tag-seconds", 15),
                getConfig().getBoolean("pvp.logout-penalty", true),
                getConfig().getInt("pvp.actionbar-update-interval-ticks", 4));
    }
}
