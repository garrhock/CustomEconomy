package dev.smpeconomy;

import dev.smpeconomy.api.CustomEconomyAPI;
import dev.smpeconomy.command.EcoAdminCommand;
import dev.smpeconomy.gui.ShopExploitValidator;
import dev.smpeconomy.command.MultiCommand;
import dev.smpeconomy.command.PlayerShopCommand;
import dev.smpeconomy.command.SellCommand;
import dev.smpeconomy.command.ShopCommand;
import dev.smpeconomy.command.WorthCommand;
import dev.smpeconomy.command.WorthsCommand;
import dev.smpeconomy.config.ConfigManager;
import dev.smpeconomy.database.DatabaseManager;
import dev.smpeconomy.database.repository.MarketRepository;
import dev.smpeconomy.database.repository.PlayerRepository;
import dev.smpeconomy.database.repository.PlayerShopRepository;
import dev.smpeconomy.database.repository.ProgressionRepository;
import dev.smpeconomy.database.repository.TransactionRepository;
import dev.smpeconomy.gui.GuiManager;
import dev.smpeconomy.hook.PAPIHook;
import dev.smpeconomy.hook.VaultHook;
import dev.smpeconomy.service.ChatInputService;
import dev.smpeconomy.service.MarketService;
import dev.smpeconomy.service.MultiplierService;
import dev.smpeconomy.service.PlayerShopService;
import dev.smpeconomy.service.SellService;
import dev.smpeconomy.service.ShopService;
import dev.smpeconomy.service.WorthService;
import dev.smpeconomy.shards.ShardsModule;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

@SuppressWarnings("UnstableApiUsage")
public final class CustomEconomy extends JavaPlugin implements Listener {

    private static CustomEconomy instance;

    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private PlayerRepository playerRepository;
    private TransactionRepository transactionRepository;
    private ProgressionRepository progressionRepository;
    private MarketRepository marketRepository;
    private PlayerShopRepository playerShopRepository;
    private MultiplierService multiplierService;
    private WorthService worthService;
    private MarketService marketService;
    private SellService sellService;
    private ShopService shopService;
    private PlayerShopService playerShopService;
    private VaultHook vaultHook;
    private ShardsModule shardsModule;
    private CustomEconomyAPI api;
    private ChatInputService chatInputService;

    private BukkitTask txFlushTask;
    private BukkitTask progressionSaveTask;
    private BukkitTask marketTickTask;
    private BukkitTask playerShopExpiryTask;

    @Override
    public void onLoad() {
        instance = this;
    }

    @Override
    public void onEnable() {
        // ── Config ───────────────────────────────────────────────────────────
        configManager = new ConfigManager(this);
        configManager.load();

        // ── Database ─────────────────────────────────────────────────────────
        databaseManager = new DatabaseManager(this, configManager);
        if (!databaseManager.initialize()) {
            getSLF4JLogger().error("Database initialization failed. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        playerRepository      = new PlayerRepository(databaseManager);
        transactionRepository = new TransactionRepository(databaseManager);
        progressionRepository = new ProgressionRepository(databaseManager);
        marketRepository      = new MarketRepository(databaseManager, configManager.isMysql());
        playerShopRepository  = new PlayerShopRepository(databaseManager);

        // ── Services ─────────────────────────────────────────────────────────
        multiplierService = new MultiplierService(progressionRepository);
        worthService      = new WorthService(configManager);
        worthService.setMultiplierService(multiplierService);

        marketService = new MarketService(marketRepository, configManager, getLogger());
        marketService.initialize(worthService.getItemWorthMap());
        worthService.setMarketService(marketService);

        // ── Vault ────────────────────────────────────────────────────────────
        vaultHook = new VaultHook(this);
        if (!vaultHook.hook()) {
            getSLF4JLogger().error("Vault/Economy provider not found. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        sellService = new SellService(worthService, vaultHook, transactionRepository,
                                      configManager, multiplierService, marketService);
        shopService = new ShopService(vaultHook, transactionRepository, marketService);
        playerShopService = new PlayerShopService(
            this, playerShopRepository, transactionRepository, vaultHook,
            worthService, configManager,
            configManager.getPlayerShopMaxListings(), getLogger());

        // ── Shards (second currency) ─────────────────────────────────────────
        shardsModule = new ShardsModule(this, databaseManager, configManager);
        shardsModule.enable();

        // ── Public API ───────────────────────────────────────────────────────
        api = new CustomEconomyAPI(worthService, sellService);

        // ── Chat input service ───────────────────────────────────────────────
        chatInputService = new ChatInputService(this);

        // ── Commands (Paper brigadier) ───────────────────────────────────────
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, e -> {
            var registrar = e.registrar();
            new SellCommand().register(registrar);
            new WorthCommand(this, worthService).register(registrar);
            new WorthsCommand(configManager, worthService, marketService).register(registrar);
            new EcoAdminCommand(this, configManager, worthService, marketService, shardsModule).register(registrar);
            new ShopCommand(configManager, shopService, marketService).register(registrar);
            new PlayerShopCommand(configManager, playerShopService).register(registrar);
            new MultiCommand(multiplierService).register(registrar);
        });

        // ── PlaceholderAPI ───────────────────────────────────────────────────
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new PAPIHook(this, worthService).register();
            getSLF4JLogger().info("PlaceholderAPI hooked.");
        }

        // ── Event listeners ──────────────────────────────────────────────────
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new GuiManager(), this);
        getServer().getPluginManager().registerEvents(chatInputService, this);

        // ── Transaction flush task (async, every 2 s) ────────────────────────
        long flushTicks = Math.max(20L, (configManager.getTxBatchIntervalMs() / 50));
        txFlushTask = getServer().getScheduler().runTaskTimerAsynchronously(
            this, transactionRepository::flush, flushTicks, flushTicks);

        // ── Progression save (async, every 5 min) ────────────────────────────
        progressionSaveTask = getServer().getScheduler().runTaskTimerAsynchronously(
            this, multiplierService::saveAll, 6000L, 6000L);

        // ── Market price tick (async) ─────────────────────────────────────────
        if (configManager.isMarketEnabled()) {
            long tickTicks = configManager.getMarketTickIntervalSeconds() * 20L;
            marketTickTask = getServer().getScheduler().runTaskTimerAsynchronously(
                this, marketService::runUpdate, tickTicks, tickTicks);
            getSLF4JLogger().info("Dynamic market pricing enabled. Tick interval: "
                + configManager.getMarketTickIntervalSeconds() + "s");
        }

        // ── Player shop expiry cleanup (async DB, Vault refunds hop to main) ──
        playerShopExpiryTask = getServer().getScheduler().runTaskTimerAsynchronously(
            this, playerShopService::runExpiryCleanup, 6000L, 6000L);

        ShopExploitValidator.validate(worthService, configManager.getShopSections(), getLogger());
        getSLF4JLogger().info("CustomEconomy enabled. Items loaded: " + worthService.getItemCount());
    }

    @Override
    public void onDisable() {
        if (txFlushTask != null)           txFlushTask.cancel();
        if (progressionSaveTask != null)   progressionSaveTask.cancel();
        if (marketTickTask != null)        marketTickTask.cancel();
        if (playerShopExpiryTask != null)  playerShopExpiryTask.cancel();

        // Shards flush before the pool closes — they share it.
        if (shardsModule != null)          shardsModule.disable();

        if (transactionRepository != null) transactionRepository.flushSync();
        if (multiplierService != null)     multiplierService.saveAll();
        if (databaseManager != null)       databaseManager.shutdown();

        getSLF4JLogger().info("CustomEconomy disabled.");
    }

    // ── Player lifecycle ─────────────────────────────────────────────────────

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        var uuid = event.getPlayer().getUniqueId();
        playerRepository.upsertPlayer(uuid, event.getPlayer().getName());
        multiplierService.loadPlayer(uuid);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        multiplierService.evictPlayer(event.getPlayer().getUniqueId());
    }

    // ── Static access ────────────────────────────────────────────────────────

    public static CustomEconomy getInstance()           { return instance; }

    public ConfigManager getConfigManager()             { return configManager; }
    public WorthService getWorthService()               { return worthService; }
    public SellService getSellService()                 { return sellService; }
    public VaultHook getVaultHook()                     { return vaultHook; }
    public MultiplierService getMultiplierService()     { return multiplierService; }
    public ShardsModule getShardsModule()               { return shardsModule; }
    public TransactionRepository getTransactionRepository() { return transactionRepository; }
    public ShopService getShopService()                 { return shopService; }
    public PlayerShopService getPlayerShopService()     { return playerShopService; }
    public MarketService getMarketService()             { return marketService; }
    public ChatInputService getChatInputService()       { return chatInputService; }
    public CustomEconomyAPI getAPI()                    { return api; }
}
