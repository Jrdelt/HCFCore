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
    private GhostPlayerManager ghostPlayerManager;

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
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to initialize the database, disabling.", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        userManager = new UserManager(this, storage);
        messages = new Messages(this, userManager);
        messages.load();
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

        rebootManager = new RebootManager(this, messages);
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

        captureEventManager = new CaptureEventManager(this, messages, rallyManager, staffManager);
        captureEventManager.load();
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
        spawnerMenuListener = new me.vertex.core.spawner.SpawnerMenuListener(spawnerManager, staffManager, messages,
                rallyManager);
        Bukkit.getPluginManager().registerEvents(spawnerMenuListener, this);
        Bukkit.getScheduler().runTaskTimer(this, spawnerManager::manualSpawnTick, 100L, 100L);
        mobStackListener = new me.vertex.core.spawner.MobStackListener(this, spawnerManager);
        Bukkit.getPluginManager().registerEvents(mobStackListener, this);
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
        boosterService = new me.vertex.core.booster.BoosterService(this);
        boosterService.reloadConfig();
        boosterService.register(new me.vertex.core.booster.BackpackBoosterSource(backpackManager));
        boosterService.register(new me.vertex.core.booster.FactionUpgradeBoosterSource(factionUpgradeManager));
        me.vertex.core.booster.BoostersCommand boostersCommand =
                new me.vertex.core.booster.BoostersCommand(boosterService, messages);
        getCommand("boosters").setExecutor(boostersCommand);
        getCommand("boosters").setTabCompleter(boostersCommand);
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.booster.BoostersMenuListener(boosterService, messages), this);
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

        coinflipManager = new me.vertex.core.coinflip.CoinflipManager(this, coinflipStorage, messages, combatManager);
        coinflipManager.load();
        coinflipManager.loadState();
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

        shopManager = new me.vertex.core.shop.ShopManager(this, shopStorage);
        shopManager.load();
        shopManager.loadState();
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.shop.ShopMenuListener(shopManager, spawnerManager, messages), this);
        me.vertex.core.shop.ShopCommand shopCommand =
                new me.vertex.core.shop.ShopCommand(shopManager, spawnerManager, messages);
        getCommand("shop").setExecutor(shopCommand);
        getCommand("shop").setTabCompleter(shopCommand);
        // SpawnerMenuListener needs a way back to the shop's category picker
        // now that /spawners no longer exists as its own entry point -- shop
        // isn't constructed yet at spawner-setup time above, so it's wired in
        // here instead of the constructor.
        spawnerMenuListener.setShopManager(shopManager);
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
        auctionManager.loadState();
        Bukkit.getPluginManager().registerEvents(
                new me.vertex.core.auction.AuctionMenuListener(auctionManager, messages), this);
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
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
        }
        if (sandBotManager != null) {
            sandBotManager.stop();
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

    public void reload() {
        reloadConfig();
        NumberFormatConfig.load(this);
        validateRuntimeDependencies();

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
