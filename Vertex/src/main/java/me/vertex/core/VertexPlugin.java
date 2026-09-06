package me.vertex.core;

import me.vertex.core.ability.AbilitiesCommand;
import me.vertex.core.ability.AbilityManager;
import me.vertex.core.ability.AbilityMenuListener;
import me.vertex.core.ability.CooldownsCommand;
import me.vertex.core.ability.AntiBlockupBoneListener;
import me.vertex.core.ability.FakePearlListener;
import me.vertex.core.ability.NoPearlSpawnListener;
import me.vertex.core.ability.PearlVelocityListener;
import me.vertex.core.ability.GetItemCommand;
import me.vertex.core.ability.GrapplingHookListener;
import me.vertex.core.ability.LeapListener;
import me.vertex.core.ability.FallDamageImmunityListener;
import me.vertex.core.ability.MageSpellListener;
import me.vertex.core.ability.NinjaStarListener;
import me.vertex.core.ability.PortableBardListener;
import me.vertex.core.ability.RepairListener;
import me.vertex.core.ability.RogueBackstabListener;
import me.vertex.core.ability.SwitcherSnowballListener;
import me.vertex.core.ability.TimeWarpPearlListener;
import me.vertex.core.ability.VanillaCooldownListener;
import me.vertex.core.ability.VanillaCooldownManager;
import me.vertex.core.chat.ChatFormatterListener;
import me.vertex.core.ability.PearlStunnerListener;
import me.vertex.core.ability.JumpBoostFeatherListener;
import me.vertex.core.ability.RabbitsFeedListener;
import me.vertex.core.factions.FactionCommandListener;
import me.vertex.core.kit.KitCommand;
import me.vertex.core.kit.KitManager;
import me.vertex.core.kit.KitMenuListener;
import me.vertex.core.kit.KitsCommand;
import me.vertex.core.lang.Messages;
import me.vertex.core.lang.LanguageCommand;
import me.vertex.core.listener.CombatListener;
import me.vertex.core.listener.PlayerConnectionListener;
import me.vertex.core.pvp.CombatCheckCommand;
import me.vertex.core.pvp.ArcherTagListener;
import me.vertex.core.pvp.ArcherTagManager;
import me.vertex.core.pvp.CombatManager;
import me.vertex.core.pvp.FullHealSplashListener;
import me.vertex.core.pvp.HungerManagementListener;
import me.vertex.core.pvp.CombatTagCommand;
import me.vertex.core.pvp.UncombatCommand;
import me.vertex.core.pvp.LegacyCombatManager;
import me.vertex.core.reboot.NextRebootCommand;
import me.vertex.core.reboot.RebootCommand;
import me.vertex.core.reboot.RebootManager;
import me.vertex.core.scoreboard.ScoreboardManager;
import me.vertex.core.faction.RallyCommand;
import me.vertex.core.faction.RallyManager;
import me.vertex.core.faction.FactionUpgradeManager;
import me.vertex.core.faction.FactionUpgradeStorage;
import me.vertex.core.staff.DeathListener;
import me.vertex.core.staff.DeathManager;
import me.vertex.core.staff.InvRestoreMenuListener;
import me.vertex.core.staff.RollbackCommand;
import me.vertex.core.storage.Database;
import me.vertex.core.storage.SqlStorage;
import me.vertex.core.storage.Storage;
import me.vertex.core.user.UserManager;
import me.vertex.core.nametag.NametagManager;
import me.vertex.core.nametag.NametagListener;
import me.vertex.core.staff.EndseeCommand;
import me.vertex.core.staff.FreezeCommand;
import me.vertex.core.staff.FreezeListener;
import me.vertex.core.staff.InvseeCommand;
import me.vertex.core.staff.InvseeMenuListener;
import me.vertex.core.staff.StaffBuildCommand;
import me.vertex.core.staff.StaffBuildListener;
import me.vertex.core.staff.StaffChatCommand;
import me.vertex.core.staff.StaffChatListener;
import me.vertex.core.staff.StaffCommand;
import me.vertex.core.staff.StaffManager;
import me.vertex.core.staff.VanishCommand;
import me.vertex.core.staff.VanishListener;
import me.vertex.core.tag.TagManager;
import me.vertex.core.tag.TagsCommand;
import me.vertex.core.tag.TagMenuListener;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class VertexPlugin extends JavaPlugin implements Listener {

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
    private me.vertex.core.spawner.SpawnerStorage spawnerStorage;
    private me.vertex.core.spawner.SpawnerManager spawnerManager;
    private me.vertex.core.spawner.MobStackListener mobStackListener;
    private me.vertex.core.collector.ChunkCollectorStorage chunkCollectorStorage;
    private me.vertex.core.collector.ChunkCollectorManager chunkCollectorManager;
    private me.vertex.core.collector.ChunkCollectorListener chunkCollectorListener;
    private me.vertex.core.blueprint.BlueprintStorage blueprintStorage;
    private me.vertex.core.blueprint.BlueprintManager blueprintManager;
    private me.vertex.core.blueprint.BlueprintListener blueprintListener;
    private RebootManager rebootManager;
    private PlayerConnectionListener playerConnectionListener;
    private RepairListener repairListener;
    private TagManager tagManager;
    private VanillaCooldownManager vanillaCooldownManager;
    private ArcherTagManager archerTagManager;
    private DeathManager deathManager;
    private RallyManager rallyManager;
    private RallyCommand rallyCommand;
    private FactionUpgradeStorage factionUpgradeStorage;
    private FactionUpgradeManager factionUpgradeManager;
    private me.vertex.core.faction.FactionBankStorage factionBankStorage;
    private me.vertex.core.faction.FactionBankManager factionBankManager;
    private me.vertex.core.faction.FactionBankMenu factionBankMenu;
    private final AtomicBoolean storageMigrationRunning = new AtomicBoolean();
    private ArcherTagListener archerTagListener;
    private CombatListener combatListener;
    private HungerManagementListener hungerManagementListener;
    private NametagManager nametagManager;
    private StaffManager staffManager;

    @Override
    public void onLoad() {
        // FactionsUUID closes its third-party command registry during its
        // onEnable(). Since Vertex depends on it, this onLoad hook runs after
        // FactionsUUID has initialized the registry but before it closes.
        RallyCommand.registerFactionsSubcommand(this, () -> rallyCommand);
        me.vertex.core.faction.FactionBankMenu.registerFactionsSubcommand(this, () -> factionBankMenu);
    }

    @Override
    public void onEnable() {
        printStartupBanner();

        Bukkit.getPluginManager().registerEvents(this, this);

        saveDefaultConfig();

        if (!validateRuntimeDependencies()) {
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        try {
            database = new Database(getConfig(), getDataFolder());
            storage = new SqlStorage(database);
            // One-time schema check at boot; the recurring load/save calls
            // made during gameplay are the ones that must stay off the main
            // thread, and they do (see UserManager/KitManager).
            storage.init();
            spawnerStorage = new me.vertex.core.spawner.SpawnerStorage(database);
            spawnerStorage.init();
            chunkCollectorStorage = new me.vertex.core.collector.ChunkCollectorStorage(database);
            chunkCollectorStorage.init();
            blueprintStorage = new me.vertex.core.blueprint.BlueprintStorage(database);
            blueprintStorage.init();
            factionUpgradeStorage = new FactionUpgradeStorage(database);
            factionUpgradeStorage.init();
            factionBankStorage = new me.vertex.core.faction.FactionBankStorage(database);
            factionBankStorage.init();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to initialize the database, disabling.", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        userManager = new UserManager(this, storage);
        messages = new Messages(this, userManager);
        messages.load();
        abilityManager = new AbilityManager(this, storage);
        abilityManager.load();
        Bukkit.getPluginManager().registerEvents(abilityManager, this);
        kitManager = new KitManager(this, storage, userManager, messages, abilityManager);
        kitManager.load();
        kitManager.start();
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
                getConfig().getInt("pvp.post-kill-combat-seconds", 5),
                getConfig().getBoolean("pvp.logout-penalty", true),
                getConfig().getInt("pvp.actionbar-update-interval-ticks", 4),
                getConfig().getString("pvp.actionbar.vs-server", ""),
                getConfig().getString("pvp.actionbar.vs-player", ""),
                getConfig().getString("pvp.actionbar.vs-unknown", ""));
        combatManager.start();

        legacyCombatManager = new LegacyCombatManager(this);
        Bukkit.getPluginManager().registerEvents(legacyCombatManager, this);
        legacyCombatManager.start();

        rebootManager = new RebootManager(this, messages);
        rebootManager.start();

        deathManager = new DeathManager(this, storage);
        Bukkit.getPluginManager().registerEvents(new DeathListener(deathManager), this);
        Bukkit.getPluginManager().registerEvents(new InvRestoreMenuListener(this, deathManager, messages), this);

        rallyManager = new RallyManager(this, messages);
        rallyCommand = new RallyCommand(this, rallyManager, messages);
        getCommand("frally").setExecutor(rallyCommand);
        Bukkit.getPluginManager().registerEvents(rallyCommand, this);
        me.vertex.core.faction.RallyPermissionMenu rallyPermissionMenu =
                new me.vertex.core.faction.RallyPermissionMenu(this, rallyManager, messages);
        Bukkit.getPluginManager().registerEvents(rallyPermissionMenu, this);
        factionUpgradeManager = new FactionUpgradeManager(this, factionUpgradeStorage);
        factionUpgradeManager.load();
        Bukkit.getPluginManager().registerEvents(factionUpgradeManager, this);
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.faction.FactionUpgradeMenu(this, factionUpgradeManager, messages), this);
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.faction.FactionUpgradeEffectsListener(this, factionUpgradeManager), this);
        factionBankManager = new me.vertex.core.faction.FactionBankManager(this, factionBankStorage);
        factionBankManager.load();
        factionBankMenu = new me.vertex.core.faction.FactionBankMenu(this, factionBankManager, rallyManager, messages);
        Bukkit.getPluginManager().registerEvents(factionBankManager, this);
        Bukkit.getPluginManager().registerEvents(factionBankMenu, this);

        nametagManager = new NametagManager(this);
        Bukkit.getPluginManager().registerEvents(new NametagListener(nametagManager), this);

        staffManager = new StaffManager(this);
        if (scoreboardManager != null) {
            scoreboardManager.setStaffManager(staffManager);
        }
        Bukkit.getPluginManager().registerEvents(new VanishListener(staffManager), this);
        Bukkit.getPluginManager().registerEvents(new StaffChatListener(staffManager), this);
        Bukkit.getPluginManager().registerEvents(new StaffBuildListener(staffManager), this);
        Bukkit.getPluginManager().registerEvents(new FreezeListener(staffManager, messages), this);
        Bukkit.getPluginManager().registerEvents(new InvseeMenuListener(this), this);
        getCommand("staff").setExecutor(new StaffCommand(staffManager, messages));
        getCommand("vanish").setExecutor(new VanishCommand(staffManager, messages));
        getCommand("staffchat").setExecutor(new StaffChatCommand(staffManager, messages));
        getCommand("staffbuild").setExecutor(new StaffBuildCommand(staffManager, messages));
        FreezeCommand freezeCommand = new FreezeCommand(staffManager, messages);
        getCommand("freeze").setExecutor(freezeCommand);
        getCommand("freeze").setTabCompleter(freezeCommand);
        InvseeCommand invseeCommand = new InvseeCommand(messages);
        getCommand("invsee").setExecutor(invseeCommand);
        getCommand("invsee").setTabCompleter(invseeCommand);
        EndseeCommand endseeCommand = new EndseeCommand(messages);
        getCommand("endersee").setExecutor(endseeCommand);
        getCommand("endersee").setTabCompleter(endseeCommand);

        spawnerManager = new me.vertex.core.spawner.SpawnerManager(this, spawnerStorage);
        spawnerManager.setFactionUpgradeManager(factionUpgradeManager);
        spawnerManager.load();
        spawnerManager.loadSpawnersFromDatabase();
        factionUpgradeManager.setSpawnerRetune(spawnerManager::retuneAll);
        spawnerManager.retuneAll();
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.spawner.SpawnerListener(spawnerManager, staffManager, messages, rallyManager), this);
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.spawner.SpawnerMobListener(this, spawnerManager), this);
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.spawner.SpawnerClaimListener(spawnerManager, messages), this);
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.spawner.SpawnerMenuListener(spawnerManager, staffManager, messages, rallyManager), this);
        getCommand("spawners").setExecutor(new me.vertex.core.spawner.SpawnerCommand(spawnerManager, messages));
        Bukkit.getScheduler().runTaskTimer(this, spawnerManager::manualSpawnTick, 100L, 100L);
        mobStackListener = new me.vertex.core.spawner.MobStackListener(this, spawnerManager);
        Bukkit.getPluginManager().registerEvents(mobStackListener, this);
        Bukkit.getScheduler().runTaskTimer(this, mobStackListener::consolidateStacks, 40L, 40L);

        chunkCollectorManager = new me.vertex.core.collector.ChunkCollectorManager(this, chunkCollectorStorage, messages);
        chunkCollectorManager.load();
        chunkCollectorManager.loadIndexFromDatabase();
        reconcileLoadedManagedBlocks();
        chunkCollectorListener = new me.vertex.core.collector.ChunkCollectorListener(
                this, chunkCollectorManager, staffManager, messages, rallyManager);
        Bukkit.getPluginManager().registerEvents(chunkCollectorListener, this);
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.collector.ChunkCollectorClaimListener(chunkCollectorManager, messages), this);
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.faction.FactionRenameListener(spawnerManager, chunkCollectorManager), this);
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.collector.ChunkCollectorMenuListener(chunkCollectorManager, staffManager, messages,
                        rallyManager), this);
        Bukkit.getScheduler().runTaskTimer(this, chunkCollectorListener::scanForMissedItems,
                chunkCollectorManager.scanIntervalTicks(), chunkCollectorManager.scanIntervalTicks());
        me.vertex.core.collector.ChunkCollectorCommand chunkCollectorCommand =
                new me.vertex.core.collector.ChunkCollectorCommand(chunkCollectorManager, messages);
        getCommand("chunkcollector").setExecutor(chunkCollectorCommand);
        getCommand("chunkcollector").setTabCompleter(chunkCollectorCommand);

        // The Blueprint Base Builder is meaningless without FAWE (it's the
        // only way this plugin can load a .schem file) and DecentHolograms
        // (the required progress display) -- rather than risk a
        // NoClassDefFoundError the moment a player ever touches the
        // feature, it's simply never wired up at all if either is absent.
        if (Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit") != null
                && Bukkit.getPluginManager().getPlugin("DecentHolograms") != null) {
            blueprintManager = new me.vertex.core.blueprint.BlueprintManager(this, blueprintStorage);
            blueprintManager.load();
            blueprintListener = new me.vertex.core.blueprint.BlueprintListener(this, blueprintManager, messages);
            Bukkit.getPluginManager().registerEvents(blueprintListener, this);
            Bukkit.getPluginManager().registerEvents(
                    new me.vertex.core.blueprint.BlueprintMenuListener(blueprintManager, blueprintListener, messages), this);
            Bukkit.getPluginManager().registerEvents(
                    new me.vertex.core.blueprint.BlueprintActivationMenuListener(blueprintListener, blueprintManager, messages), this);
            blueprintListener.resumeAll();
            // Blueprint blocks are paced every tick so a structure visibly
            // grows row-by-row instead of appearing in one-second chunks.
            Bukkit.getScheduler().runTaskTimer(this, blueprintListener::tickBuilds, 1L, 1L);
            me.vertex.core.blueprint.BlueprintCommand blueprintCommand =
                    new me.vertex.core.blueprint.BlueprintCommand(this, blueprintManager, messages);
            getCommand("blueprint").setExecutor(blueprintCommand);
            getCommand("blueprint").setTabCompleter(blueprintCommand);
        } else {
            getLogger().info("Blueprint Base Builder disabled -- requires both FastAsyncWorldEdit and DecentHolograms.");
        }

        combatListener = new CombatListener(this, combatManager, messages);
        Bukkit.getPluginManager().registerEvents(combatListener, this);
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
        Bukkit.getPluginManager().registerEvents(new NoPearlSpawnListener(this, messages), this);
        Bukkit.getPluginManager().registerEvents(new PearlVelocityListener(this), this);
        hungerManagementListener = new HungerManagementListener(this);
        Bukkit.getPluginManager().registerEvents(hungerManagementListener, this);
        Bukkit.getPluginManager().registerEvents(
                new NinjaStarListener(this, abilityManager, userManager, combatManager, messages), this);
        Bukkit.getPluginManager().registerEvents(new FallDamageImmunityListener(), this);
        Bukkit.getPluginManager().registerEvents(new FullHealSplashListener(), this);
        Bukkit.getPluginManager().registerEvents(new VanillaCooldownListener(this, messages, vanillaCooldownManager), this);
        archerTagListener = new ArcherTagListener(this, archerTagManager);
        Bukkit.getPluginManager().registerEvents(archerTagListener, this);
        // Periodic cleanup of expired archer tag entries to prevent unbounded map growth
        Bukkit.getScheduler().runTaskTimer(this, archerTagManager::cleanupExpired, 300L, 300L);
        Bukkit.getPluginManager().registerEvents(new PearlStunnerListener(this, abilityManager, userManager, messages), this);
        Bukkit.getPluginManager().registerEvents(new RabbitsFeedListener(this, abilityManager, userManager, messages), this);
        Bukkit.getPluginManager().registerEvents(new JumpBoostFeatherListener(this, abilityManager, userManager, messages), this);
        Bukkit.getPluginManager().registerEvents(new TagMenuListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ChatFormatterListener(tagManager, this), this);
        Bukkit.getPluginManager().registerEvents(new FactionCommandListener(this, messages), this);

        KitCommand kitCommand = new KitCommand(this, kitManager, messages);
        getCommand("kit").setExecutor(kitCommand);
        getCommand("kit").setTabCompleter(kitCommand);
        getCommand("kits").setExecutor(new KitsCommand(this, kitManager, userManager, messages));
        Bukkit.getPluginManager().registerEvents(new KitMenuListener(this, kitManager, messages), this);

        getCommand("vertex").setExecutor(new VertexCommand(this, messages));

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
        }
    }

    /**
     * SQL only tells us about rows that finished saving. Scan chunks Paper
     * already has loaded so a valid Collector/Spawner PDC record left by an
     * interrupted asynchronous write is recovered before any player can use it.
     */
    private void reconcileLoadedManagedBlocks() {
        if (spawnerManager == null || chunkCollectorManager == null) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                try {
                    spawnerManager.reconcileChunk(chunk);
                    chunkCollectorManager.reconcileChunk(chunk);
                } catch (Exception e) {
                    getLogger().log(java.util.logging.Level.WARNING,
                            "Failed to reconcile managed blocks in loaded chunk " + world.getName() + ","
                                    + chunk.getX() + "," + chunk.getZ(), e);
                }
            }
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
        if (deathManager != null) {
            deathManager.awaitWrites();
        }
        if (tagManager != null) {
            tagManager.awaitWrites();
        }
        if (spawnerManager != null) {
            spawnerManager.awaitWrites();
        }
        if (chunkCollectorManager != null) {
            chunkCollectorManager.awaitWrites();
        }
        if (blueprintManager != null) {
            blueprintManager.pauseForRestart();
            blueprintManager.awaitWrites();
        }
        if (rallyManager != null) {
            rallyManager.shutdown();
        }
        if (factionUpgradeManager != null) {
            factionUpgradeManager.awaitWrites();
        }
        if (factionBankManager != null) {
            factionBankManager.awaitWrites();
        }
        if (nametagManager != null) {
            nametagManager.shutdown();
        }
        FakePearlListener.clearAll();
        if (storage != null) {
            storage.close();
        }
    }

    /**
     * Prints the Vertex logo to the console before anything else runs, so
     * it's the first thing a server operator sees for this plugin on every
     * boot -- along with the exact version running and where to find the
     * source/report issues. Written straight to the console sender (not
     * {@link #getLogger()}) so the plugin's own log prefix isn't repeated
     * on every line of the art.
     */
    private void printStartupBanner() {
        String[] logo = {
                "##      ##  ##########  ########    ##########  ##########  ##      ##",
                "##      ##  ##          ##      ##      ##      ##          ##      ##",
                "##      ##  ########    ##      ##      ##      ########      ##  ##  ",
                "##      ##  ##          ########        ##      ##              ##    ",
                "  ##  ##    ##          ##  ##          ##      ##            ##  ##  ",
                "  ##  ##    ##          ##    ##        ##      ##          ##      ##",
                "    ##      ##########  ##      ##      ##      ##########  ##      ##",
        };
        String version = getDescription().getVersion();
        String authors = String.join(", ", getDescription().getAuthors());

        CommandSender console = Bukkit.getConsoleSender();
        console.sendMessage(Component.empty());
        for (String line : logo) {
            console.sendMessage(Component.text(line, NamedTextColor.AQUA));
        }
        console.sendMessage(Component.empty());
        console.sendMessage(Component.text("  Version ", NamedTextColor.GRAY)
                .append(Component.text(version, NamedTextColor.WHITE))
                .append(Component.text("   Developed by ", NamedTextColor.GRAY))
                .append(Component.text(authors, NamedTextColor.WHITE)));
        console.sendMessage(Component.text("  https://github.com/Jrdelt/HCFCore", NamedTextColor.DARK_AQUA));
        console.sendMessage(Component.empty());
    }

    public static boolean hasRequiredDependency(PluginManager pluginManager) {
        Plugin factions = pluginManager.getPlugin("FactionsUUID");
        return factions != null;
    }

    public boolean validateRuntimeDependencies() {
        if (!hasRequiredDependency(Bukkit.getPluginManager())) {
            getLogger().severe("Missing required dependency: FactionsUUID. Disabling Vertex.");
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
        if (abilityManager != null) {
            abilityManager.load();
        }
        if (spawnerManager != null) {
            spawnerManager.load();
        }
        if (factionUpgradeManager != null) {
            factionUpgradeManager.reloadConfig();
            if (spawnerManager != null) {
                spawnerManager.retuneAll();
            }
        }
        if (chunkCollectorManager != null) {
            chunkCollectorManager.load();
        }
        if (blueprintManager != null) {
            blueprintManager.load();
        }
        if (kitManager != null) {
            kitManager.load();
        }
        if (tagManager != null) {
            tagManager.load();
        }
        if (nametagManager != null) {
            nametagManager.reload();
        }

        if (scoreboardManager != null) {
            scoreboardManager.stop();
            scoreboardManager = null;
        }
        if (userManager != null && abilityManager != null) {
            scoreboardManager = new ScoreboardManager(this, getConfig(), userManager, abilityManager);
            scoreboardManager.setStaffManager(staffManager);
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
                // setup() just replaced this player's scoreboard object
                // with a blank one -- nametag teams lived on the old one,
                // so it needs every online player's nametag re-applied.
                if (nametagManager != null) {
                    nametagManager.applyAllNametagsTo(player);
                }
            }
        }

        if (combatManager != null) {
            combatManager.reconfigure(
                    getConfig().getInt("pvp.combat-tag-seconds", 30),
                    getConfig().getInt("pvp.post-kill-combat-seconds", 5),
                    getConfig().getBoolean("pvp.logout-penalty", true),
                    getConfig().getInt("pvp.actionbar-update-interval-ticks", 4),
                    getConfig().getString("pvp.actionbar.vs-server", ""),
                    getConfig().getString("pvp.actionbar.vs-player", ""),
                    getConfig().getString("pvp.actionbar.vs-unknown", ""));
        }
        if (archerTagListener != null) {
            archerTagListener.reloadConfig();
        }
        if (hungerManagementListener != null) {
            hungerManagementListener.reloadConfig();
        }
        if (combatListener != null) {
            combatListener.reloadConfig();
        }
        if (legacyCombatManager != null) {
            legacyCombatManager.reconfigure();
        }
        if (rebootManager != null) {
            rebootManager.reconfigure();
        }
    }

    /** Manual entity-clear for stacked mobs; see MobStackListener#clearAll. */
    public int clearMobStacks() {
        return mobStackListener != null ? mobStackListener.clearAll() : 0;
    }

    /** The live connection pool, for the storage-migration command. */
    public Database database() {
        return database;
    }

    /** Which backend is actually in use right now, not what config.yml says. */
    public Database.Dialect storageDialect() {
        return database != null ? database.dialect() : Database.Dialect.SQLITE;
    }

    /**
     * A storage migration is a point-in-time copy, so it is intentionally
     * allowed only while no player can mutate data and no Blueprint is
     * placing blocks. The caller must invoke {@link #finishStorageMigration()}.
     */
    public boolean beginStorageMigration() {
        if (!Bukkit.getOnlinePlayers().isEmpty()
                || blueprintManager != null && !blueprintManager.activeBuilds().isEmpty()) {
            return false;
        }
        return storageMigrationRunning.compareAndSet(false, true);
    }

    /** Called off-thread after {@link #beginStorageMigration()} to drain source writes. */
    public void awaitStorageWritesForMigration() {
        if (kitManager != null) kitManager.awaitWrites();
        if (abilityManager != null) abilityManager.awaitWrites();
        if (languageCommand != null) languageCommand.awaitWrites();
        if (deathManager != null) deathManager.awaitWrites();
        if (tagManager != null) tagManager.awaitWrites();
        if (spawnerManager != null) spawnerManager.awaitWrites();
        if (chunkCollectorManager != null) chunkCollectorManager.awaitWrites();
        if (blueprintManager != null) blueprintManager.awaitWrites();
        if (factionUpgradeManager != null) factionUpgradeManager.awaitWrites();
        if (factionBankManager != null) factionBankManager.awaitWrites();
    }

    public void finishStorageMigration() {
        storageMigrationRunning.set(false);
    }

    /** Keeps an accepted migration a true point-in-time copy. */
    @EventHandler
    public void onPlayerLogin(PlayerLoginEvent event) {
        if (storageMigrationRunning.get()) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                    messages.get(null, "admin.storage-login-blocked"));
        }
    }
}
