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
import me.vertex.core.pvp.GhostPlayerManager;
import me.vertex.core.pvp.FullHealSplashListener;
import me.vertex.core.pvp.HungerManagementListener;
import me.vertex.core.pvp.CombatTagCommand;
import me.vertex.core.pvp.UncombatCommand;
import me.vertex.core.pvp.LegacyCombatManager;
import me.vertex.core.reboot.NextRebootCommand;
import me.vertex.core.reboot.RebootCommand;
import me.vertex.core.reboot.RebootManager;
import me.vertex.core.faction.RallyCommand;
import me.vertex.core.faction.RallyManager;
import me.vertex.core.faction.FactionUpgradeManager;
import me.vertex.core.faction.FactionUpgradeStorage;
import me.vertex.core.capture.CaptureCommand;
import me.vertex.core.capture.CaptureEventManager;
import me.vertex.core.capture.CaptureEventType;
import me.vertex.core.staff.DeathListener;
import me.vertex.core.staff.DeathManager;
import me.vertex.core.staff.InvRestoreMenuListener;
import me.vertex.core.staff.RollbackCommand;
import me.vertex.core.storage.Database;
import me.vertex.core.storage.SqlStorage;
import me.vertex.core.storage.Storage;
import me.vertex.core.user.UserManager;
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
import me.vertex.core.util.NumberFormatConfig;
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
    private me.vertex.core.util.ChatAmountPrompt chatAmountPrompt;
    private LanguageCommand languageCommand;
    private KitManager kitManager;
    private AbilityManager abilityManager;
    private CombatManager combatManager;
    private LegacyCombatManager legacyCombatManager;
    private me.vertex.core.spawner.SpawnerStorage spawnerStorage;
    private me.vertex.core.spawner.SpawnerManager spawnerManager;
    private me.vertex.core.claims.ClaimStorage claimStorage;
    private me.vertex.core.claims.BaseClaimManager baseClaimManager;
    private me.vertex.core.claims.RaidClaimManager raidClaimManager;
    private me.vertex.core.shield.ShieldStorage shieldStorage;
    private me.vertex.core.shield.ShieldManager shieldManager;
    private me.vertex.core.chunkbuster.ChunkBusterStorage chunkBusterStorage;
    private me.vertex.core.chunkbuster.ChunkBusterManager chunkBusterManager;
    private me.vertex.core.bucket.SourceBucketManager sourceBucketManager;
    private me.vertex.core.faction.FTopStorage fTopStorage;
    private me.vertex.core.faction.FTopManager fTopManager;
    private me.vertex.core.faction.PvpTopStorage pvpTopStorage;
    private me.vertex.core.faction.PvpTopManager pvpTopManager;
    private me.vertex.core.spawner.SpawnerMenuListener spawnerMenuListener;
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
    private CaptureEventManager captureEventManager;
    private FactionUpgradeStorage factionUpgradeStorage;
    private FactionUpgradeManager factionUpgradeManager;
    private me.vertex.core.faction.FactionBankStorage factionBankStorage;
    private me.vertex.core.faction.FactionBankManager factionBankManager;
    private me.vertex.core.faction.FactionBankMenu factionBankMenu;
    private final AtomicBoolean storageMigrationRunning = new AtomicBoolean();
    private ArcherTagListener archerTagListener;
    private CombatListener combatListener;
    private me.vertex.core.pvp.LootProtectionListener lootProtectionListener;
    private HungerManagementListener hungerManagementListener;
    private me.vertex.core.listener.ItemRestrictionListener itemRestrictionListener;
    private StaffManager staffManager;
    private me.vertex.core.backpack.BackpackManager backpackManager;
    private me.vertex.core.booster.BoosterService boosterService;
    private me.vertex.core.wand.WandManager wandManager;
    private me.vertex.core.mine.MineManager mineManager;
    private me.vertex.core.mine.MineKothManager mineKothManager;
    private me.vertex.core.mine.HotZoneManager hotZoneManager;
    private me.vertex.core.menu.MenuRegistry menuRegistry;
    private me.vertex.core.coinflip.CoinflipStorage coinflipStorage;
    private me.vertex.core.coinflip.CoinflipManager coinflipManager;
    private org.bukkit.scheduler.BukkitTask coinflipGuiRefreshTask;
    private me.vertex.core.shop.ShopStorage shopStorage;
    private me.vertex.core.shop.ShopManager shopManager;
    private me.vertex.core.sandbot.SandBotManager sandBotManager;
    private me.vertex.core.placeholderapi.VertexPlaceholderExpansion placeholderExpansion;
    private org.bukkit.scheduler.BukkitTask shopDecayTask;
    private me.vertex.core.auction.AuctionStorage auctionStorage;
    private me.vertex.core.auction.AuctionManager auctionManager;
    private org.bukkit.scheduler.BukkitTask auctionSweepTask;
    private me.vertex.core.trade.TradeStorage tradeStorage;
    private me.vertex.core.trade.TradeManager tradeManager;
    private me.vertex.core.preferences.AnnouncementPreferenceStorage announcementPreferenceStorage;
    private me.vertex.core.preferences.AnnouncementPreferenceManager announcementPreferenceManager;
    private GhostPlayerManager ghostPlayerManager;
    private me.vertex.core.gc.GcStorage gcStorage;
    private me.vertex.core.gc.GcManager gcManager;
    private me.vertex.core.gc.GcInteropHook gcInteropHook;
    private me.vertex.core.gc.GcMenu gcMenu;
    private me.vertex.core.dupe.DupeStorage dupeStorage;
    private me.vertex.core.dupe.DupeManager dupeManager;
    private me.vertex.core.item.TrackedItemIds trackedItemIds;
    private me.vertex.core.enchant.EnchantManager enchantManager;
    private me.vertex.core.performance.PerformanceManager performanceManager;
    private me.vertex.core.zone.ZoneManager zoneManager;
    private me.vertex.core.portal.PortalManager portalManager;

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
        NumberFormatConfig.load(this);

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
            fTopStorage = new me.vertex.core.faction.FTopStorage(database);
            fTopStorage.init();
            pvpTopStorage = new me.vertex.core.faction.PvpTopStorage(database);
            pvpTopStorage.init();
            chunkCollectorStorage = new me.vertex.core.collector.ChunkCollectorStorage(database);
            chunkCollectorStorage.init();
            blueprintStorage = new me.vertex.core.blueprint.BlueprintStorage(database);
            blueprintStorage.init();
            factionUpgradeStorage = new FactionUpgradeStorage(database);
            factionUpgradeStorage.init();
            factionBankStorage = new me.vertex.core.faction.FactionBankStorage(database);
            factionBankStorage.init();
            coinflipStorage = new me.vertex.core.coinflip.CoinflipStorage(database);
            coinflipStorage.init();
            shopStorage = new me.vertex.core.shop.ShopStorage(database);
            shopStorage.init();
            auctionStorage = new me.vertex.core.auction.AuctionStorage(database);
            auctionStorage.init();
            tradeStorage = new me.vertex.core.trade.TradeStorage(database);
            tradeStorage.init();
            announcementPreferenceStorage = new me.vertex.core.preferences.AnnouncementPreferenceStorage(database);
            announcementPreferenceStorage.init();
            gcStorage = new me.vertex.core.gc.GcStorage(database);
            gcStorage.init();
            dupeStorage = new me.vertex.core.dupe.DupeStorage(database);
            dupeStorage.init();
            claimStorage = new me.vertex.core.claims.ClaimStorage(database);
            claimStorage.init();
            shieldStorage = new me.vertex.core.shield.ShieldStorage(database);
            shieldStorage.init();
            chunkBusterStorage = new me.vertex.core.chunkbuster.ChunkBusterStorage(database);
            chunkBusterStorage.init();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to initialize the database, disabling.", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        userManager = new UserManager(this, storage);
        messages = new Messages(this, userManager);
        messages.load();

        // Performance framework: OFF by default, diagnostic-only, never
        // changes gameplay -- see PerformanceManager's class doc. Loaded
        // this early so every manager constructed below can be wired to it
        // (via an optional setPerformanceManager setter) before its own
        // first load() call.
        performanceManager = new me.vertex.core.performance.PerformanceManager(this);
        performanceManager.load();

        announcementPreferenceManager = new me.vertex.core.preferences.AnnouncementPreferenceManager(this,
                announcementPreferenceStorage, messages);
        Bukkit.getPluginManager().registerEvents(announcementPreferenceManager, this);
        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            announcementPreferenceManager.loadPlayer(player.getUniqueId());
        }
        me.vertex.core.preferences.SettingsCommand settingsCommand = new me.vertex.core.preferences.SettingsCommand(
                announcementPreferenceManager, messages);
        getCommand("settings").setExecutor(settingsCommand);
        Bukkit.getPluginManager().registerEvents(new me.vertex.core.preferences.SettingsMenuListener(), this);
        chatAmountPrompt = new me.vertex.core.util.ChatAmountPrompt(this);
        Bukkit.getPluginManager().registerEvents(chatAmountPrompt, this);
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
        itemRestrictionListener = new me.vertex.core.listener.ItemRestrictionListener(this, messages);
        Bukkit.getPluginManager().registerEvents(itemRestrictionListener, this);

        rebootManager = new RebootManager(this, messages, announcementPreferenceManager);
        rebootManager.start();

        deathManager = new DeathManager(this, storage);
        Bukkit.getPluginManager().registerEvents(new DeathListener(deathManager, messages), this);
        Bukkit.getPluginManager().registerEvents(new InvRestoreMenuListener(this, deathManager, messages), this);

        rallyManager = new RallyManager(this, messages);
        rallyCommand = new RallyCommand(this, rallyManager, messages);
        getCommand("frally").setExecutor(rallyCommand);
        Bukkit.getPluginManager().registerEvents(rallyCommand, this);
        me.vertex.core.faction.RallyPermissionMenu rallyPermissionMenu = new me.vertex.core.faction.RallyPermissionMenu(
                this, rallyManager, messages);
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
        factionBankManager.migrateNativeTntBanks();
        factionBankMenu = new me.vertex.core.faction.FactionBankMenu(this, factionBankManager, factionUpgradeManager,
                rallyManager, messages, chatAmountPrompt);
        me.vertex.core.faction.TntFillCommand tntFillCommand =
                new me.vertex.core.faction.TntFillCommand(this, messages, factionBankManager);
        getCommand("tntfill").setExecutor(tntFillCommand);
        getCommand("tntfill").setTabCompleter(tntFillCommand);
        Bukkit.getPluginManager().registerEvents(factionBankManager, this);
        Bukkit.getPluginManager().registerEvents(factionBankMenu, this);

        staffManager = new StaffManager(this);

        pvpTopManager = new me.vertex.core.faction.PvpTopManager(this, pvpTopStorage);
        pvpTopManager.load();
        pvpTopManager.loadState();
        me.vertex.core.faction.PvpTopCommand pvpTopCommand =
                new me.vertex.core.faction.PvpTopCommand(this, pvpTopManager, messages);
        Bukkit.getPluginManager().registerEvents(pvpTopCommand, this);
        getCommand("pvptop").setExecutor(pvpTopCommand);
        getCommand("pvptop").setTabCompleter(pvpTopCommand);

        captureEventManager = new CaptureEventManager(this, messages, rallyManager, staffManager,
                announcementPreferenceManager);
        captureEventManager.load();
        captureEventManager.setPvpTopManager(pvpTopManager);
        captureEventManager.start();
        Bukkit.getPluginManager().registerEvents(captureEventManager, this);
        CaptureCommand kothCommand = new CaptureCommand(captureEventManager, messages, CaptureEventType.KOTH);
        getCommand("koth").setExecutor(kothCommand);
        getCommand("koth").setTabCompleter(kothCommand);
        CaptureCommand outpostCommand = new CaptureCommand(captureEventManager, messages, CaptureEventType.OUTPOST);
        getCommand("outpost").setExecutor(outpostCommand);
        getCommand("outpost").setTabCompleter(outpostCommand);
        Bukkit.getPluginManager().registerEvents(new VanishListener(staffManager), this);
        Bukkit.getPluginManager().registerEvents(new StaffChatListener(staffManager), this);
        Bukkit.getPluginManager().registerEvents(new StaffBuildListener(staffManager), this);
        Bukkit.getPluginManager().registerEvents(new FreezeListener(staffManager, messages), this);
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.staff.PunishmentCombatListener(this, combatManager, messages), this);
        Bukkit.getPluginManager().registerEvents(new InvseeMenuListener(this, messages), this);
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
        fTopManager = new me.vertex.core.faction.FTopManager(this, spawnerManager, fTopStorage);
        fTopManager.load();
        fTopManager.loadState();
        spawnerManager.setFTopManager(fTopManager);
        fTopManager.start();
        me.vertex.core.faction.FTopCommand fTopCommand = new me.vertex.core.faction.FTopCommand(this, fTopManager, messages);
        getCommand("ftopforcecheck").setExecutor(fTopCommand);
        Bukkit.getPluginManager().registerEvents(fTopCommand, this);
        factionUpgradeManager.setSpawnerRetune(spawnerManager::retuneAll);
        spawnerManager.retuneAll();
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.spawner.SpawnerListener(spawnerManager, staffManager, messages, rallyManager), this);
        Bukkit.getPluginManager().registerEvents(new me.vertex.core.spawner.SpawnerItemProtectionListener(), this);
        me.vertex.core.spawner.SpawnerMobListener spawnerMobListener =
                new me.vertex.core.spawner.SpawnerMobListener(this, spawnerManager);
        Bukkit.getPluginManager().registerEvents(spawnerMobListener, this);
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.spawner.SpawnerClaimListener(spawnerManager, messages), this);
        spawnerMenuListener = new me.vertex.core.spawner.SpawnerMenuListener(spawnerManager, staffManager, messages,
                rallyManager);
        Bukkit.getPluginManager().registerEvents(spawnerMenuListener, this);
        Bukkit.getScheduler().runTaskTimer(this, spawnerManager::manualSpawnTick, 100L, 100L);
        mobStackListener = new me.vertex.core.spawner.MobStackListener(this, spawnerManager);
        Bukkit.getPluginManager().registerEvents(mobStackListener, this);
        // Lets the spawn override tell a stacking merge apart from another
        // plugin cancelling the spawn outright.
        spawnerMobListener.setMobStacking(mobStackListener);
        Bukkit.getScheduler().runTaskTimer(this, mobStackListener::consolidateStacks, 40L, 40L);

        chunkCollectorManager = new me.vertex.core.collector.ChunkCollectorManager(this, chunkCollectorStorage,
                messages);
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
                        rallyManager, chatAmountPrompt),
                this);
        Bukkit.getScheduler().runTaskTimer(this, chunkCollectorListener::scanForMissedItems,
                chunkCollectorManager.scanIntervalTicks(), chunkCollectorManager.scanIntervalTicks());
        me.vertex.core.collector.ChunkCollectorCommand chunkCollectorCommand = new me.vertex.core.collector.ChunkCollectorCommand(
                chunkCollectorManager, messages);
        getCommand("chunkcollector").setExecutor(chunkCollectorCommand);
        getCommand("chunkcollector").setTabCompleter(chunkCollectorCommand);

        backpackManager = new me.vertex.core.backpack.BackpackManager(this, messages);
        backpackManager.load();

        // Reads the bonuses the Backpack and faction-upgrade systems already
        // apply; it grants nothing itself, so wiring it cannot change what a
        // player receives.
        menuRegistry = new me.vertex.core.menu.MenuRegistry(this,
                java.util.List.of(me.vertex.core.booster.BoostersMenu.MENU_ID,
                        me.vertex.core.mine.MinesMenu.MENU_ID,
                        me.vertex.core.event.EventsMenu.MENU_ID,
                        me.vertex.core.gc.GcMenu.MENU_ID,
                        me.vertex.core.claims.BaseClaimMenu.MENU_ID,
                        me.vertex.core.chunkbuster.ChunkBusterMenu.MENU_ID,
                        me.vertex.core.enchant.EnchantApplyGui.MENU_ID));
        menuRegistry.load();

        // Base Claims / Raid Claims: Base Claim state is loaded fully into
        // memory (anchors + connected regions), Raid Claims are a bounded
        // set of currently-tracked chunk timers -- see ClaimStorage's class
        // doc and RaidClaimManager's "persist the deadline, catch up on
        // restart" doc for why.
        baseClaimManager = new me.vertex.core.claims.BaseClaimManager(this, claimStorage, spawnerManager);
        baseClaimManager.load();
        baseClaimManager.loadState();
        raidClaimManager = new me.vertex.core.claims.RaidClaimManager(this, claimStorage);
        raidClaimManager.load();
        raidClaimManager.loadState();
        raidClaimManager.recoverState();
        me.vertex.core.claims.BaseClaimCommand baseClaimCommand =
                new me.vertex.core.claims.BaseClaimCommand(this, baseClaimManager, messages, menuRegistry);
        Bukkit.getPluginManager().registerEvents(baseClaimCommand, this);
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.claims.BaseClaimMenuListener(baseClaimManager, messages, menuRegistry), this);
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.claims.ClaimEventListener(baseClaimManager, raidClaimManager, messages), this);

        // Faction Shield: schedule/override state is loaded fully into
        // memory (bounded by the number of factions that have ever had a
        // Shield row), same "durable table, live cache" split as
        // BaseClaimManager above -- see ShieldManager's class doc for the
        // "let the current window finish" and override freeze/resume logic.
        shieldManager = new me.vertex.core.shield.ShieldManager(this, shieldStorage, baseClaimManager);
        shieldManager.load();
        shieldManager.loadState();
        shieldManager.start();
        baseClaimManager.setShieldActiveQuery(shieldManager::isShieldActive);
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.shield.ShieldCommand(this, shieldManager, messages), this);
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.shield.ShieldCombatListener(shieldManager, messages), this);
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.shield.ShieldFactionLifecycleListener(shieldManager), this);

        // TNT / Explosion / Wither rules (Phase 3): explosion block damage
        // is stripped out only where it touches a Base Claim, regardless of
        // Shield state -- Base Claims disable explosion block damage on
        // their own, Shield only ever gates combat (see ShieldCombatListener
        // above). Raid Claims and wilderness are left exactly as
        // FactionsUUID's own territory protection already handles them.
        // Player/mob damage from any explosion is cancelled everywhere,
        // unconditionally. Withers are disabled server-wide.
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.claims.ExplosionProtectionListener(baseClaimManager::isBaseClaim), this);
        Bukkit.getPluginManager().registerEvents(new me.vertex.core.listener.WitherPreventionListener(), this);

        // Chunk Busters (Phase 4): every live-FactionsUUID/combat lookup is
        // injected as a plain functional reference -- see the class doc for
        // why (mirrors ExplosionProtectionListener's Predicate<Location>
        // decoupling above). Wired after CombatManager/SpawnerManager
        // (both already exist by here) and before Shop, because Shop's
        // listener renders the Chunk Buster entries in Raiding Materials.
        chunkBusterManager = new me.vertex.core.chunkbuster.ChunkBusterManager(this, chunkBusterStorage,
                spawnerManager,
                me.vertex.core.factions.FactionsHook::getClaimFactionId,
                me.vertex.core.factions.FactionsHook::getClaimFactionTag,
                me.vertex.core.factions.FactionsHook::getFactionId,
                me.vertex.core.faction.RallyManager::roleId,
                combatManager::isTagged,
                player -> rallyManager.canUse(player, "chunkbuster-use"));
        chunkBusterManager.setPerformanceManager(performanceManager);
        chunkBusterManager.load();
        chunkBusterManager.loadState();
        chunkBusterManager.recoverAbandonedOperations();
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.chunkbuster.ChunkBusterListener(chunkBusterManager, messages, menuRegistry), this);
        me.vertex.core.chunkbuster.ChunkBusterCommand chunkBusterCommand =
                new me.vertex.core.chunkbuster.ChunkBusterCommand(this, chunkBusterManager, messages);
        getCommand("chunkbusters").setExecutor(chunkBusterCommand);
        getCommand("chunkbusters").setTabCompleter(chunkBusterCommand);

        // Source Buckets (Phase 5): same injected-functional-reference
        // testability shape as Chunk Busters above, plus BaseClaimManager's
        // own query for the per-variant base-claim-only gate. Unlike Chunk
        // Busters there is no persisted state at all -- a Source Bucket is
        // a physical, permanently-reusable item, so possession of the item
        // *is* the record; see SourceBucketManager's class doc for why no
        // new table exists. The listener needs ShopManager (for its "back
        // to shop" button), so it's registered further down, alongside
        // ChunkBusterMenuListener, once ShopManager exists.
        sourceBucketManager = new me.vertex.core.bucket.SourceBucketManager(this,
                me.vertex.core.factions.FactionsHook::getClaimFactionId,
                me.vertex.core.factions.FactionsHook::getClaimFactionTag,
                baseClaimManager::isBaseClaim,
                combatManager::isTagged);
        sourceBucketManager.load();

        // GC is a self-hosted, third currency -- its own database is the sole
        // balance authority, wired up the same way the other self-contained
        // economy-like features above are (storage -> manager -> menu ->
        // command -> listeners), then handed to Coinflip/Auction House so
        // they can offer it as a wager/listing currency.
        gcManager = new me.vertex.core.gc.GcManager(this, gcStorage);
        gcManager.load();
        gcManager.loadState();
        gcInteropHook = new me.vertex.core.gc.GcInteropHook(this);
        gcInteropHook.configure(gcManager.interopCommandTemplate());
        gcMenu = new me.vertex.core.gc.GcMenu(this, gcManager, messages, menuRegistry);
        Bukkit.getPluginManager().registerEvents(gcMenu, this);
        Bukkit.getPluginManager().registerEvents(new me.vertex.core.gc.GcLogMenuListener(gcManager, gcMenu, messages), this);
        me.vertex.core.gc.GcCommand gcCommand = new me.vertex.core.gc.GcCommand(this, gcManager, gcMenu,
                gcInteropHook, messages);
        getCommand("gc").setExecutor(gcCommand);
        getCommand("gc").setTabCompleter(gcCommand);

        // Dupe investigation: reuses the item-identity + suspected-duplicate
        // queue built as the anti-dupe framework other item-tagging features
        // (Custom Enchantments, below) route detected duplicates through,
        // rather than building a second detector. TrackedItemIds is shared
        // with EnchantManager below -- DupeManager#shouldTrack treats any
        // item carrying a real ItemKind as worth tracking, so tagging is the
        // entire integration a new feature needs.
        trackedItemIds = new me.vertex.core.item.TrackedItemIds(this);
        dupeManager = new me.vertex.core.dupe.DupeManager(this, dupeStorage, messages, trackedItemIds);
        dupeManager.setPerformanceManager(performanceManager);
        dupeManager.load();
        Bukkit.getPluginManager().registerEvents(new me.vertex.core.dupe.DupeListener(dupeManager), this);
        me.vertex.core.dupe.DupeCommand dupeCommand = new me.vertex.core.dupe.DupeCommand(this, dupeManager, messages);
        getCommand("dupe").setExecutor(dupeCommand);
        getCommand("dupe").setTabCompleter(dupeCommand);

        // Custom Enchantments: Rune rolling is a direct right-click action;
        // /runes (and its aliases) provides the dedicated Rune catalog.
        enchantManager = new me.vertex.core.enchant.EnchantManager(this, trackedItemIds);
        enchantManager.load();
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.enchant.RuneListener(enchantManager, messages, menuRegistry), this);
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.enchant.EnchantApplyGuiListener(this, enchantManager, messages, menuRegistry), this);
        me.vertex.core.enchant.EnchantCommand enchantCommand =
                new me.vertex.core.enchant.EnchantCommand(enchantManager, messages);
        getCommand("enchant").setExecutor(enchantCommand);
        getCommand("enchant").setTabCompleter(enchantCommand);

        boosterService = new me.vertex.core.booster.BoosterService(this);
        boosterService.reloadConfig();
        boosterService.register(new me.vertex.core.booster.BackpackBoosterSource(backpackManager));
        boosterService.register(new me.vertex.core.booster.FactionUpgradeBoosterSource(factionUpgradeManager));
        me.vertex.core.booster.BoostersCommand boostersCommand =
                new me.vertex.core.booster.BoostersCommand(boosterService, messages, menuRegistry);
        getCommand("boosters").setExecutor(boostersCommand);
        getCommand("boosters").setTabCompleter(boostersCommand);
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.booster.BoostersMenuListener(boosterService, messages, menuRegistry), this);
        me.vertex.core.backpack.BackpackFilterManager backpackFilterManager = new me.vertex.core.backpack.BackpackFilterManager(
                this);
        backpackFilterManager.load();
        me.vertex.core.backpack.BackpackInteractListener backpackInteractListener = new me.vertex.core.backpack.BackpackInteractListener(
                backpackManager, messages);
        Bukkit.getPluginManager().registerEvents(backpackInteractListener, this);
        me.vertex.core.backpack.BackpackAutoStoreListener backpackAutoStoreListener = new me.vertex.core.backpack.BackpackAutoStoreListener(
                backpackManager, backpackFilterManager, messages);
        Bukkit.getPluginManager().registerEvents(backpackAutoStoreListener, this);
        mobStackListener.setPlayerDropRouter(backpackAutoStoreListener::routePlayerMobDrops);
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.backpack.BackpackMenuListener(backpackManager, messages), this);
        me.vertex.core.backpack.BackpackCommand backpackCommand = new me.vertex.core.backpack.BackpackCommand(
                backpackManager, messages, backpackInteractListener);
        getCommand("backpack").setExecutor(backpackCommand);
        getCommand("backpack").setTabCompleter(backpackCommand);
        me.vertex.core.backpack.BackpackFilterCommand backpackFilterCommand = new me.vertex.core.backpack.BackpackFilterCommand(
                backpackFilterManager, messages);
        getCommand("filter").setExecutor(backpackFilterCommand);
        getCommand("filter").setTabCompleter(backpackFilterCommand);

        coinflipManager = new me.vertex.core.coinflip.CoinflipManager(this, coinflipStorage, messages, combatManager,
                announcementPreferenceManager);
        coinflipManager.load();
        coinflipManager.loadState();
        coinflipManager.setGcManager(gcManager);
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.coinflip.CoinflipMenuListener(coinflipManager, messages), this);
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.coinflip.CoinflipWagerMenuListener(coinflipManager, messages), this);
        Bukkit.getPluginManager().registerEvents(new me.vertex.core.coinflip.CoinflipJoinListener(coinflipManager),
                this);
        Bukkit.getPluginManager().registerEvents(new me.vertex.core.coinflip.CoinflipAnimationMenuListener(), this);
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.coinflip.CoinflipMatchReviewMenuListener(coinflipManager, messages), this);
        me.vertex.core.coinflip.CoinflipCommand coinflipCommand = new me.vertex.core.coinflip.CoinflipCommand(
                coinflipManager, messages);
        getCommand("cf").setExecutor(coinflipCommand);
        getCommand("cf").setTabCompleter(coinflipCommand);
        // Bukkit cancels this along with every other of this plugin's tasks on disable
        // --
        // no explicit cancel()/reschedule needed, same as every other fixed-cadence
        // task here.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            coinflipManager.sweepExpiredItemMatches();
            coinflipManager.pruneLog();
        }, 600L, 600L);
        coinflipGuiRefreshTask = Bukkit.getScheduler().runTaskTimer(this, this::refreshOpenCoinflipBrowsers,
                coinflipManager.guiRefreshIntervalTicks(), coinflipManager.guiRefreshIntervalTicks());

        shopManager = new me.vertex.core.shop.ShopManager(this, shopStorage, boosterService);
        shopManager.load();
        shopManager.loadState();
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.shop.ShopMenuListener(shopManager, spawnerManager, chunkBusterManager,
                        sourceBucketManager, messages),
                this);
        // Chunk Busters have a confirmation GUI; their purchase entries are
        // rendered in Raiding Materials by ShopMenu.
        Bukkit.getPluginManager().registerEvents(new me.vertex.core.chunkbuster.ChunkBusterMenuListener(
                chunkBusterManager, messages, menuRegistry), this);
        // Source Buckets' item-use listener. Purchases are normal Raiding
        // Materials entries, not a secondary menu.
        Bukkit.getPluginManager().registerEvents(new me.vertex.core.bucket.SourceBucketListener(
                sourceBucketManager, messages), this);
        // The dedicated /runes purchase GUI is independent from /shop.
        Bukkit.getPluginManager().registerEvents(new me.vertex.core.enchant.RuneShopMenuListener(
                enchantManager, messages), this);
        mineManager = new me.vertex.core.mine.MineManager(this, messages);
        mineManager.load();
        me.vertex.core.mine.MineTeleportManager mineTeleportManager =
                new me.vertex.core.mine.MineTeleportManager(this, mineManager, combatManager, messages);
        Bukkit.getPluginManager().registerEvents(mineTeleportManager, this);
        backpackAutoStoreListener.setMineRegionPredicate(location -> mineManager.regionAt(location) != null);
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.mine.MineListener(mineManager, boosterService, messages, backpackAutoStoreListener), this);

        me.vertex.core.mine.MineKothStorage mineKothStorage = new me.vertex.core.mine.MineKothStorage(database);
        try {
            mineKothStorage.init();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to initialise Mine KOTH storage.", e);
        }
        mineKothManager = new me.vertex.core.mine.MineKothManager(this, mineManager, mineKothStorage, messages,
                announcementPreferenceManager);
        mineKothManager.load();
        boosterService.register(new me.vertex.core.mine.MineKothBoosterSource(mineKothManager));

        me.vertex.core.mine.HotZoneStorage hotZoneStorage = new me.vertex.core.mine.HotZoneStorage(database);
        try {
            hotZoneStorage.init();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to initialise Hot Zone storage.", e);
        }
        hotZoneManager = new me.vertex.core.mine.HotZoneManager(this, mineManager, hotZoneStorage, messages,
                announcementPreferenceManager);
        hotZoneManager.load();
        mineManager.setHotZones(hotZoneManager);
        boosterService.register(new me.vertex.core.mine.HotZoneBoosterSource(mineManager, hotZoneManager));

        me.vertex.core.mine.MinesCommand minesCommand = new me.vertex.core.mine.MinesCommand(
                mineManager, mineKothManager, hotZoneManager, boosterService, messages, menuRegistry);
        getCommand("mines").setExecutor(minesCommand);
        getCommand("mines").setTabCompleter(minesCommand);
        Bukkit.getPluginManager().registerEvents(new me.vertex.core.mine.MinesMenuListener(
                mineManager, mineKothManager, hotZoneManager, boosterService, messages, menuRegistry,
                mineTeleportManager), this);
        me.vertex.core.event.EventsCommand eventsCommand = new me.vertex.core.event.EventsCommand(
                mineManager, mineKothManager, hotZoneManager, messages, menuRegistry);
        getCommand("events").setExecutor(eventsCommand);
        Bukkit.getPluginManager().registerEvents(new me.vertex.core.event.EventsMenuListener(), this);
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            private long ticks;

            @Override
            public void run() {
                ticks += 20L;
                for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
                    me.vertex.core.event.EventsMenu.refreshOpen(player, mineManager, mineKothManager,
                            hotZoneManager, messages, menuRegistry, ticks);
                }
            }
        }, 20L, 20L);

        wandManager = new me.vertex.core.wand.WandManager(this);
        wandManager.load();
        me.vertex.core.wand.WandCommand wandCommand =
                new me.vertex.core.wand.WandCommand(wandManager, messages);
        getCommand("wand").setExecutor(wandCommand);
        getCommand("wand").setTabCompleter(wandCommand);
        Bukkit.getPluginManager().registerEvents(new me.vertex.core.wand.WandListener(this, wandManager,
                shopManager, chunkCollectorManager, factionBankManager, factionUpgradeManager, messages,
                backpackFilterManager, rallyManager), this);

        me.vertex.core.shop.ShopCommand shopCommand =
                new me.vertex.core.shop.ShopCommand(shopManager, spawnerManager, messages);
        getCommand("shop").setExecutor(shopCommand);
        getCommand("shop").setTabCompleter(shopCommand);
        shopDecayTask = Bukkit.getScheduler().runTaskTimer(this, shopManager::decayTick,
                shopManager.decayIntervalTicks(), shopManager.decayIntervalTicks());

        sandBotManager = loadSandBotManager();
        sandBotManager.start();
        Plugin fancyNpcs = Bukkit.getPluginManager().getPlugin("FancyNpcs");
        if (fancyNpcs != null && fancyNpcs.isEnabled()) {
            Bukkit.getPluginManager().registerEvents(new me.vertex.core.sandbot.SandBotListener(sandBotManager, messages),
                    this);
        } else {
            getLogger().warning("FancyNPCs is not enabled; Sand Bots cannot be placed until it is installed.");
        }
        me.vertex.core.sandbot.SandBotCommand sandBotCommand = new me.vertex.core.sandbot.SandBotCommand(sandBotManager,
                messages);
        getCommand("sandbot").setExecutor(sandBotCommand);
        getCommand("sandbot").setTabCompleter(sandBotCommand);

        auctionManager = new me.vertex.core.auction.AuctionManager(this, auctionStorage);
        auctionManager.load();
        auctionManager.setGcManager(gcManager);
        auctionManager.loadState();
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.auction.AuctionMenuListener(this, auctionManager, messages), this);
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.auction.AuctionHubMenuListener(auctionManager, messages), this);
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.auction.AuctionHistoryMenuListener(auctionManager, messages), this);
        Bukkit.getPluginManager().registerEvents(new me.vertex.core.auction.AuctionJoinListener(auctionManager), this);
        me.vertex.core.auction.AuctionCommand auctionCommand = new me.vertex.core.auction.AuctionCommand(auctionManager,
                messages);
        getCommand("ah").setExecutor(auctionCommand);
        getCommand("ah").setTabCompleter(auctionCommand);
        auctionSweepTask = Bukkit.getScheduler().runTaskTimer(this, auctionManager::sweepExpired,
                auctionManager.sweepIntervalTicks(), auctionManager.sweepIntervalTicks());

        tradeManager = new me.vertex.core.trade.TradeManager(this, tradeStorage, messages);
        tradeManager.load();
        tradeManager.restoreEscrow();
        Bukkit.getPluginManager().registerEvents(new me.vertex.core.trade.TradeListener(this, tradeManager, messages),
                this);
        me.vertex.core.trade.TradeCommand tradeCommand = new me.vertex.core.trade.TradeCommand(tradeManager, messages);
        getCommand("trade").setExecutor(tradeCommand);
        getCommand("trade").setTabCompleter(tradeCommand);
        getCommand("tradetoggle").setExecutor(new me.vertex.core.trade.TradeToggleCommand(tradeManager, messages));
        getCommand("tradeadmin").setExecutor(new me.vertex.core.trade.TradeAdminCommand(tradeManager, messages));
        me.vertex.core.trade.TradeHistoryCommand tradeHistoryCommand = new me.vertex.core.trade.TradeHistoryCommand(
                tradeManager, messages);
        getCommand("tradehistory").setExecutor(tradeHistoryCommand);
        getCommand("tradehistory").setTabCompleter(tradeHistoryCommand);
        getCommand("tradelogs").setExecutor(tradeHistoryCommand);
        getCommand("tradelogs").setTabCompleter(tradeHistoryCommand);
        Bukkit.getPluginManager().registerEvents(new me.vertex.core.trade.TradeHistoryListener(tradeManager, messages),
                this);
        Bukkit.getScheduler().runTaskTimer(this, tradeManager::sweep, 20L, 20L);

        initializeBlueprintFeature();
        initializeGhostPlayerFeature();

        combatListener = new CombatListener(this, combatManager, messages);
        Bukkit.getPluginManager().registerEvents(combatListener, this);
        lootProtectionListener = new me.vertex.core.pvp.LootProtectionListener(this, messages);
        Bukkit.getPluginManager().registerEvents(lootProtectionListener, this);
        // Haven/Riftlands deliberately plugs into the existing combat,
        // backpack, PDC identity, loot-protection, booster, and SQL services.
        // It is initialized after those dependencies and before PlaceholderAPI.
        zoneManager = new me.vertex.core.zone.ZoneManager(this, database, messages, combatManager,
                backpackManager, trackedItemIds);
        try {
            zoneManager.initStorage();
            zoneManager.load();
            zoneManager.loadState();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to initialise Haven/Riftlands zones.", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        zoneManager.setBoosterService(boosterService);
        boosterService.register(new me.vertex.core.zone.ZoneBoosterSource(zoneManager));
        me.vertex.core.zone.ZoneMenu zoneMenu = new me.vertex.core.zone.ZoneMenu(zoneManager);
        Bukkit.getPluginManager().registerEvents(zoneMenu, this);
        Bukkit.getPluginManager().registerEvents(new me.vertex.core.zone.ZoneListener(zoneManager, combatManager,
                lootProtectionListener), this);
        me.vertex.core.zone.ZoneCommand havenCommand = new me.vertex.core.zone.ZoneCommand(zoneManager, zoneMenu,
                messages, me.vertex.core.zone.ZoneType.HAVEN);
        getCommand("haven").setExecutor(havenCommand);
        getCommand("haven").setTabCompleter(havenCommand);
        me.vertex.core.zone.ZoneCommand riftlandsCommand = new me.vertex.core.zone.ZoneCommand(zoneManager, zoneMenu,
                messages, me.vertex.core.zone.ZoneType.RIFTLANDS);
        getCommand("riftlands").setExecutor(riftlandsCommand);
        getCommand("riftlands").setTabCompleter(riftlandsCommand);
        me.vertex.core.zone.ZoneCommand zonesCommand = new me.vertex.core.zone.ZoneCommand(zoneManager, zoneMenu,
                messages, null);
        getCommand("zones").setExecutor(zonesCommand);
        getCommand("zones").setTabCompleter(zonesCommand);
        zoneManager.start();
        me.vertex.core.portal.PortalStorage portalStorage = new me.vertex.core.portal.PortalStorage(database);
        portalManager = new me.vertex.core.portal.PortalManager(this, portalStorage, mineManager, zoneManager,
                combatManager, messages);
        try {
            portalManager.init();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to initialise entry portals.", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        me.vertex.core.portal.PortalCommand portalCommand = new me.vertex.core.portal.PortalCommand(portalManager,
                mineManager);
        getCommand("portal").setExecutor(portalCommand);
        getCommand("portal").setTabCompleter(portalCommand);
        Bukkit.getPluginManager().registerEvents(new me.vertex.core.portal.PortalListener(portalManager), this);
        portalManager.start();
        playerConnectionListener = new PlayerConnectionListener(userManager, combatManager);
        playerConnectionListener.setGhostPlayerManager(ghostPlayerManager);
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
        repairListener = new RepairListener(this, abilityManager, userManager, messages);
        Bukkit.getPluginManager().registerEvents(repairListener, this);
        Bukkit.getPluginManager().registerEvents(
                new SwitcherSnowballListener(this, abilityManager, userManager, messages), this);
        Bukkit.getPluginManager().registerEvents(
                new TimeWarpPearlListener(this, abilityManager, userManager, messages), this);
        Bukkit.getPluginManager().registerEvents(new NoPearlSpawnListener(this, messages), this);
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.pvp.CombatSafezoneListener(this, combatManager, messages), this);
        Bukkit.getPluginManager().registerEvents(new PearlVelocityListener(this), this);
        hungerManagementListener = new HungerManagementListener(this);
        Bukkit.getPluginManager().registerEvents(hungerManagementListener, this);
        Bukkit.getPluginManager().registerEvents(
                new NinjaStarListener(this, abilityManager, userManager, combatManager, messages), this);
        Bukkit.getPluginManager().registerEvents(new FallDamageImmunityListener(), this);
        Bukkit.getPluginManager().registerEvents(new FullHealSplashListener(), this);
        Bukkit.getPluginManager().registerEvents(new VanillaCooldownListener(this, messages, vanillaCooldownManager),
                this);
        archerTagListener = new ArcherTagListener(this, archerTagManager);
        Bukkit.getPluginManager().registerEvents(archerTagListener, this);
        // Periodic cleanup of expired archer tag entries to prevent unbounded map
        // growth
        Bukkit.getScheduler().runTaskTimer(this, archerTagManager::cleanupExpired, 300L, 300L);
        Bukkit.getPluginManager().registerEvents(new PearlStunnerListener(this, abilityManager, userManager, messages),
                this);
        Bukkit.getPluginManager().registerEvents(new RabbitsFeedListener(this, abilityManager, userManager, messages),
                this);
        Bukkit.getPluginManager()
                .registerEvents(new JumpBoostFeatherListener(this, abilityManager, userManager, messages), this);
        Bukkit.getPluginManager().registerEvents(new TagMenuListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ChatFormatterListener(tagManager, this), this);
        Bukkit.getPluginManager().registerEvents(new FactionCommandListener(this, messages), this);

        KitCommand kitCommand = new KitCommand(this, kitManager, messages);
        getCommand("kit").setExecutor(kitCommand);
        getCommand("kit").setTabCompleter(kitCommand);
        getCommand("kits").setExecutor(new KitsCommand(this, kitManager, userManager, messages));
        Bukkit.getPluginManager().registerEvents(new KitMenuListener(this, kitManager, messages), this);

        VertexCommand vertexCommand = new VertexCommand(this, messages);
        getCommand("vertex").setExecutor(vertexCommand);
        getCommand("vertex").setTabCompleter(vertexCommand);

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

        // Registered last, once every manager it can reference is
        // definitely constructed.
        if (me.vertex.core.placeholderapi.PlaceholderApiHook.isAvailable()) {
            placeholderExpansion = new me.vertex.core.placeholderapi.VertexPlaceholderExpansion(this, userManager,
                    kitManager, abilityManager, combatManager, factionBankManager, staffManager, rebootManager,
                    backpackManager, tagManager, blueprintManager, coinflipManager, auctionManager, tradeManager,
                    rallyManager, factionUpgradeManager, sandBotManager);
            placeholderExpansion.setZoneManager(zoneManager);
            placeholderExpansion.register();
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
                                    + chunk.getX() + "," + chunk.getZ(),
                            e);
                }
            }
        }
    }

    /**
     * Re-renders the Active Coinflips browser in place for every online player who
     * currently has it open.
     */
    private void refreshOpenCoinflipBrowsers() {
        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            me.vertex.core.coinflip.CoinflipMenu.refreshOpenBrowse(player, coinflipManager, messages);
        }
    }

    private me.vertex.core.sandbot.SandBotManager loadSandBotManager() {
        return new me.vertex.core.sandbot.SandBotManager(this, factionBankManager, shopManager, messages,
                getConfig().getBoolean("sandbot.enabled", true),
                getConfig().getInt("sandbot.radius-blocks", 2),
                getConfig().getLong("sandbot.tick-interval-ticks", 1L),
                getConfig().getInt("sandbot.placements-per-column-per-tick", 2),
                getConfig().getInt("sandbot.max-placements-per-tick", 50),
                org.bukkit.entity.EntityType.PLAYER);
    }

    private void applySandBotConfig() {
        if (sandBotManager == null) {
            return;
        }
        sandBotManager.reconfigure(
                getConfig().getBoolean("sandbot.enabled", true),
                getConfig().getInt("sandbot.radius-blocks", 2),
                getConfig().getLong("sandbot.tick-interval-ticks", 1L),
                getConfig().getInt("sandbot.placements-per-column-per-tick", 2),
                getConfig().getInt("sandbot.max-placements-per-tick", 50),
                org.bukkit.entity.EntityType.PLAYER);
    }

    @Override
    public void onDisable() {
        if (raidClaimManager != null) {
            raidClaimManager.shutdown();
        }
        if (shieldManager != null) {
            shieldManager.shutdown();
        }
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
        }
        if (sandBotManager != null) {
            sandBotManager.stop();
        }
        if (hotZoneManager != null) {
            hotZoneManager.shutdown();
        }
        if (zoneManager != null) {
            zoneManager.shutdown();
        }
        if (portalManager != null) {
            portalManager.shutdown();
        }
        if (mineKothManager != null) {
            mineKothManager.shutdown();
        }
        if (mineManager != null) {
            mineManager.shutdown();
        }
        if (combatManager != null) {
            combatManager.stop();
        }
        if (ghostPlayerManager != null) {
            ghostPlayerManager.shutdown();
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
            deathManager.shutdown();
        }
        if (tagManager != null) {
            tagManager.shutdown();
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
        if (captureEventManager != null) {
            captureEventManager.shutdown();
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
        if (coinflipManager != null) {
            coinflipManager.awaitWrites();
        }
        if (shopManager != null) {
            shopManager.awaitWrites();
        }
        if (auctionManager != null) {
            auctionManager.awaitWrites();
        }
        if (tradeManager != null) {
            tradeManager.shutdown();
        }
        if (announcementPreferenceManager != null) {
            announcementPreferenceManager.awaitWrites();
        }
        if (gcManager != null) {
            gcManager.awaitWrites();
        }
        if (dupeManager != null) {
            dupeManager.awaitWrites();
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

    /**
     * Wires Blueprints only while both optional runtime dependencies are enabled.
     */
    private void initializeBlueprintFeature() {
        if (blueprintManager != null) {
            return;
        }
        Plugin fawe = Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit");
        Plugin holograms = Bukkit.getPluginManager().getPlugin("DecentHolograms");
        if (fawe == null || !fawe.isEnabled() || holograms == null || !holograms.isEnabled()) {
            getLogger().info(
                    "Blueprint Base Builder disabled -- requires enabled FastAsyncWorldEdit and DecentHolograms.");
            return;
        }

        blueprintManager = new me.vertex.core.blueprint.BlueprintManager(this, blueprintStorage);
        blueprintManager.load();
        blueprintListener = new me.vertex.core.blueprint.BlueprintListener(this, blueprintManager, messages);
        Bukkit.getPluginManager().registerEvents(blueprintListener, this);
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.blueprint.BlueprintMenuListener(blueprintManager, blueprintListener, messages),
                this);
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.blueprint.BlueprintActivationMenuListener(blueprintListener, blueprintManager,
                        messages),
                this);
        blueprintListener.resumeAll();
        Bukkit.getScheduler().runTaskTimer(this, blueprintListener::tickBuilds, 1L, 1L);
        me.vertex.core.blueprint.BlueprintCommand blueprintCommand = new me.vertex.core.blueprint.BlueprintCommand(this,
                blueprintManager, messages);
        getCommand("blueprint").setExecutor(blueprintCommand);
        getCommand("blueprint").setTabCompleter(blueprintCommand);
        getLogger().info("Blueprint Base Builder enabled.");
    }

    /** Enables the native Villager-based Ghost Player feature. */
    private void initializeGhostPlayerFeature() {
        if (ghostPlayerManager != null) {
            return;
        }
        ghostPlayerManager = new GhostPlayerManager(this, combatManager, messages);
        Bukkit.getPluginManager().registerEvents(ghostPlayerManager, this);
        if (playerConnectionListener != null) {
            playerConnectionListener.setGhostPlayerManager(ghostPlayerManager);
        }
        getLogger().info("Ghost Players integration enabled (native Villagers).");
    }

    public static boolean hasRequiredDependency(PluginManager pluginManager) {
        Plugin factions = pluginManager.getPlugin("FactionsUUID");
        return factions != null && factions.isEnabled();
    }

    public boolean validateRuntimeDependencies() {
        if (!hasRequiredDependency(Bukkit.getPluginManager())) {
            getLogger().severe("Missing required dependency: FactionsUUID. Disabling Vertex.");
            return false;
        }

        List<String> optionalDependencies = List.of("Vault", "WorldGuard", "LuckPerms",
                "FastAsyncWorldEdit", "DecentHolograms", "FancyNpcs");
        for (String dependency : optionalDependencies) {
            Plugin present = Bukkit.getPluginManager().getPlugin(dependency);
            if (present == null || !present.isEnabled()) {
                getLogger().warning(
                        "Optional dependency not detected: " + dependency + ". Related features will be disabled.");
            }
        }

        return true;
    }

    /** Exposed for the /vertex spawnerinfo diagnostic. */
    public me.vertex.core.spawner.SpawnerManager spawnerManager() {
        return spawnerManager;
    }

    /** Exposed for the /vertex performance diagnostic. */
    public me.vertex.core.performance.PerformanceManager performanceManager() {
        return performanceManager;
    }

    public void reload() {
        reloadConfig();
        NumberFormatConfig.load(this);
        validateRuntimeDependencies();

        // Loaded before any manager that reports through it (Chunk Busters,
        // dupe investigation) re-runs its own load() below, so a
        // level change this reload is already in effect when they
        // re-register their scheduled-task info.
        if (performanceManager != null) {
            performanceManager.load();
        }
        if (messages != null) {
            messages.load();
        }
        if (rallyManager != null) {
            rallyManager.reloadConfig();
        }
        if (itemRestrictionListener != null) {
            itemRestrictionListener.reload();
        }
        applySandBotConfig();
        if (abilityManager != null) {
            abilityManager.load();
        }
        if (spawnerManager != null) {
            spawnerManager.load();
        }
        if (fTopManager != null) {
            fTopManager.load();
        }
        if (pvpTopManager != null) {
            pvpTopManager.load();
        }
        if (baseClaimManager != null) {
            baseClaimManager.load();
        }
        if (raidClaimManager != null) {
            raidClaimManager.load();
        }
        if (shieldManager != null) {
            shieldManager.load();
        }
        if (chunkBusterManager != null) {
            chunkBusterManager.load();
        }
        if (sourceBucketManager != null) {
            sourceBucketManager.load();
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
        } else {
            initializeBlueprintFeature();
        }
        if (captureEventManager != null) {
            captureEventManager.reload();
        }
        if (backpackManager != null) {
            backpackManager.load();
        }
        if (menuRegistry != null) {
            menuRegistry.load();
        }
        if (wandManager != null) {
            wandManager.load();
        }
        if (mineManager != null) {
            mineManager.load();
        }
        if (mineKothManager != null) {
            mineKothManager.load();
        }
        if (hotZoneManager != null) {
            hotZoneManager.load();
        }
        if (zoneManager != null) {
            zoneManager.load();
        }
        if (portalManager != null) {
            portalManager.load();
        }
        if (boosterService != null) {
            boosterService.reloadConfig();
        }
        if (coinflipManager != null) {
            long previousGuiRefreshInterval = coinflipManager.guiRefreshIntervalTicks();
            coinflipManager.load();
            if (coinflipGuiRefreshTask != null
                    && previousGuiRefreshInterval != coinflipManager.guiRefreshIntervalTicks()) {
                coinflipGuiRefreshTask.cancel();
                coinflipGuiRefreshTask = Bukkit.getScheduler().runTaskTimer(this, this::refreshOpenCoinflipBrowsers,
                        coinflipManager.guiRefreshIntervalTicks(), coinflipManager.guiRefreshIntervalTicks());
            }
        }
        if (shopManager != null) {
            long previousInterval = shopManager.decayIntervalTicks();
            shopManager.load();
            if (shopDecayTask != null && previousInterval != shopManager.decayIntervalTicks()) {
                shopDecayTask.cancel();
                shopDecayTask = Bukkit.getScheduler().runTaskTimer(this, shopManager::decayTick,
                        shopManager.decayIntervalTicks(), shopManager.decayIntervalTicks());
            }
        }
        if (auctionManager != null) {
            long previousSweepInterval = auctionManager.sweepIntervalTicks();
            auctionManager.load();
            if (auctionSweepTask != null && previousSweepInterval != auctionManager.sweepIntervalTicks()) {
                auctionSweepTask.cancel();
                auctionSweepTask = Bukkit.getScheduler().runTaskTimer(this, auctionManager::sweepExpired,
                        auctionManager.sweepIntervalTicks(), auctionManager.sweepIntervalTicks());
            }
        }
        if (tradeManager != null) {
            tradeManager.load();
        }
        if (gcManager != null) {
            gcManager.load();
            gcInteropHook.configure(gcManager.interopCommandTemplate());
        }
        if (dupeManager != null) {
            dupeManager.load();
        }
        if (enchantManager != null) {
            enchantManager.load();
        }
        if (kitManager != null) {
            kitManager.load();
        }
        if (tagManager != null) {
            tagManager.load();
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
        if (ghostPlayerManager != null) {
            ghostPlayerManager.reload();
        } else {
            initializeGhostPlayerFeature();
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
        if (lootProtectionListener != null) {
            lootProtectionListener.reload(this);
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

    /**
     * Called off-thread after {@link #beginStorageMigration()} to drain source
     * writes.
     */
    public void awaitStorageWritesForMigration() {
        if (kitManager != null)
            kitManager.awaitWrites();
        if (abilityManager != null)
            abilityManager.awaitWrites();
        if (languageCommand != null)
            languageCommand.awaitWrites();
        if (deathManager != null)
            deathManager.awaitWrites();
        if (tagManager != null)
            tagManager.awaitWrites();
        if (spawnerManager != null)
            spawnerManager.awaitWrites();
        if (chunkCollectorManager != null)
            chunkCollectorManager.awaitWrites();
        if (blueprintManager != null)
            blueprintManager.awaitWrites();
        if (factionUpgradeManager != null)
            factionUpgradeManager.awaitWrites();
        if (factionBankManager != null)
            factionBankManager.awaitWrites();
        if (coinflipManager != null)
            coinflipManager.awaitWrites();
        if (shopManager != null)
            shopManager.awaitWrites();
        if (auctionManager != null)
            auctionManager.awaitWrites();
        if (tradeManager != null)
            tradeManager.awaitWrites();
        if (gcManager != null)
            gcManager.awaitWrites();
        if (dupeManager != null)
            dupeManager.awaitWrites();
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
