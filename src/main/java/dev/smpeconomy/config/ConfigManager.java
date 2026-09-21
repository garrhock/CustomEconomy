package dev.smpeconomy.config;

import dev.smpeconomy.gui.ShopEntry;
import dev.smpeconomy.gui.ShopSection;
import dev.smpeconomy.model.ItemCategory;
import dev.smpeconomy.model.ItemWorth;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Owns all YAML configuration for the plugin.
 * Reloading is hot — call {@link #reload()} from /ecoadmin reload and the
 * rest of the system picks up fresh values on the next operation.
 */
public final class ConfigManager {

    private final JavaPlugin plugin;
    private final Logger log;

    // Parsed from config.yml — database
    private String dbType;
    private String sqliteFile;
    private String mysqlHost;
    private int    mysqlPort;
    private String mysqlDatabase;
    private String mysqlUsername;
    private String mysqlPassword;
    private int    mysqlPoolSize;
    private long   txBatchIntervalMs;

    // Parsed from config.yml — economy / sell
    private String  currencySymbol;
    private String  currencyName;
    private String  currencyNamePlural;
    private int     decimalPlaces;
    private boolean skipHotbar;
    private double  breakdownThreshold;

    // Parsed from config.yml — shop
    private double shopBuyPriceMarkup;
    private double shopConfirmAbove;

    // Parsed from config.yml — player shop
    private int playerShopMaxListings;

    // Parsed from config.yml — market
    private boolean marketEnabled;
    private long    marketTickIntervalSeconds;
    private long    marketRollingWindowHours;
    private double  marketBaselineVolume;
    private double  marketDefaultVolatility;
    private double  marketDefaultRecovery;
    private boolean marketAdaptiveDepthEnabled;
    private double  marketBaselinePerSeller;
    private int     marketMinParticipants;
    private boolean marketCircuitBreakerEnabled;
    private double  marketMaxDailyDrop;

    // Parsed from config.yml — worth tooltip
    private boolean worthTooltipEnabled;
    private String  worthTooltipFormat;
    private String  worthTooltipUnsellableFormat;
    private boolean worthTooltipShowUnsellable;
    private boolean worthTooltipCompact;
    private long    worthTooltipRefreshDelayTicks;
    private volatile Set<InventoryType> worthTooltipIgnoredTypes = Set.of();

    // Parsed from items.yml — replaced atomically on reload
    private volatile Map<String, ItemWorth> itemWorthMap = Map.of();

    // Parsed from shop.yml — replaced atomically on reload
    private volatile List<ShopSection> shopSections = List.of();

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.log    = plugin.getLogger();
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /** Initial load — call once in onEnable before any service accesses config. */
    public void load() {
        plugin.saveDefaultConfig();
        saveDefault("items.yml");
        saveDefault("shop.yml");
        parseMain(plugin.getConfig());
        parseItems();
        parseShop(); // must run after parseItems() so sell prices are available
    }

    /** Hot-reload all configuration files. Thread-safe for concurrent reads. */
    public void reload() {
        plugin.reloadConfig();
        parseMain(plugin.getConfig());
        parseItems();
        parseShop();
        log.info("Configuration reloaded. Items: " + itemWorthMap.size()
                 + "  Shop sections: " + shopSections.size());
    }

    // ── Parsing ──────────────────────────────────────────────────────────────

    private void parseMain(FileConfiguration cfg) {
        dbType          = cfg.getString("database.type", "SQLITE").toUpperCase(Locale.ROOT);
        sqliteFile      = cfg.getString("database.sqlite.file", "economy.db");
        mysqlHost       = cfg.getString("database.mysql.host", "localhost");
        mysqlPort       = cfg.getInt("database.mysql.port", 3306);
        mysqlDatabase   = cfg.getString("database.mysql.database", "customeconomy");
        mysqlUsername   = cfg.getString("database.mysql.username", "root");
        mysqlPassword   = cfg.getString("database.mysql.password", "");
        mysqlPoolSize   = cfg.getInt("database.mysql.pool-size", 10);
        txBatchIntervalMs = cfg.getLong("database.transaction-batch-interval-ms", 2000);

        currencySymbol     = cfg.getString("economy.currency-symbol", "$");
        currencyName       = cfg.getString("economy.currency-name", "Dollar");
        currencyNamePlural = cfg.getString("economy.currency-name-plural", "Dollars");
        decimalPlaces      = cfg.getInt("economy.decimal-places", 2);

        skipHotbar         = cfg.getBoolean("sell.skip-hotbar", false);
        breakdownThreshold = cfg.getDouble("sell.breakdown-threshold", 0.01);

        shopBuyPriceMarkup    = cfg.getDouble("shop.buy-price-markup", 3.3);
        shopConfirmAbove      = cfg.getDouble("shop.confirm-above", 10000.0);
        playerShopMaxListings = cfg.getInt("player-shop.max-listings", 10);

        marketEnabled             = cfg.getBoolean("market.enabled", false);
        marketTickIntervalSeconds = cfg.getLong("market.tick-interval-seconds", 300);
        marketRollingWindowHours  = cfg.getLong("market.rolling-window-hours", 24);
        marketBaselineVolume      = cfg.getDouble("market.baseline-volume", 100.0);
        marketDefaultVolatility   = cfg.getDouble("market.default-volatility", 0.20);
        marketDefaultRecovery     = cfg.getDouble("market.default-recovery", 0.05);

        marketAdaptiveDepthEnabled  = cfg.getBoolean("market.adaptive-depth.enabled", false);
        marketBaselinePerSeller     = cfg.getDouble("market.adaptive-depth.baseline-per-seller", 50.0);
        marketMinParticipants       = cfg.getInt("market.adaptive-depth.min-participants", 10);
        marketCircuitBreakerEnabled = cfg.getBoolean("market.circuit-breaker.enabled", false);
        marketMaxDailyDrop          = cfg.getDouble("market.circuit-breaker.max-daily-drop", 0.15);

        worthTooltipEnabled          = cfg.getBoolean("worth-tooltip.enabled", true);
        worthTooltipFormat           = cfg.getString("worth-tooltip.format",
                                           "<gray>Worth: <green>{currency}{stack}</green></gray>");
        worthTooltipShowUnsellable   = cfg.getBoolean("worth-tooltip.show-unsellable", false);
        worthTooltipUnsellableFormat = cfg.getString("worth-tooltip.unsellable-format",
                                           "<gray>Worth: <red>Not for sale</red></gray>");
        worthTooltipCompact          = cfg.getBoolean("worth-tooltip.compact-numbers", false);
        worthTooltipRefreshDelayTicks = cfg.getLong("worth-tooltip.refresh-delay-ticks", 20L);
        worthTooltipIgnoredTypes     = parseInventoryTypes(
                                           cfg.getStringList("worth-tooltip.ignored-inventory-types"));
    }

    /** Unknown names are logged and dropped rather than failing the whole load. */
    private Set<InventoryType> parseInventoryTypes(List<String> names) {
        EnumSet<InventoryType> set = EnumSet.noneOf(InventoryType.class);
        for (String name : names) {
            try {
                set.add(InventoryType.valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                log.warning("Unknown inventory type in worth-tooltip.ignored-inventory-types: " + name);
            }
        }
        return Collections.unmodifiableSet(set);
    }

    private void parseItems() {
        File itemsFile = new File(plugin.getDataFolder(), "items.yml");
        if (!itemsFile.exists()) {
            log.warning("items.yml not found — no items will be sellable.");
            itemWorthMap = Map.of();
            return;
        }

        FileConfiguration cfg = YamlConfiguration.loadConfiguration(itemsFile);
        ConfigurationSection items = cfg.getConfigurationSection("items");
        if (items == null) {
            log.warning("items.yml has no 'items' section.");
            itemWorthMap = Map.of();
            return;
        }

        Map<String, ItemWorth> map = new HashMap<>(512);
        for (String key : items.getKeys(false)) {
            ConfigurationSection sec = items.getConfigurationSection(key);
            if (sec == null) continue;

            double price = sec.getDouble("price", -1);
            if (price <= 0) {
                log.warning("Item '" + key + "' has no valid price — skipped.");
                continue;
            }

            try {
                ItemWorth worth = ItemWorth.builder(key)
                    .displayName(sec.getString("display-name", null))
                    .category(ItemCategory.fromString(sec.getString("category", "GENERAL")))
                    .basePrice(price)
                    .minPrice(sec.getDouble("min-price", -1))
                    .maxPrice(sec.getDouble("max-price", -1))
                    .volatility(sec.getDouble("volatility", 0.30))
                    .recoveryRate(sec.getDouble("recovery", 0.05))
                    .xpPerUnit(sec.getDouble("xp-per-unit", 1.0))
                    .buyPrice(sec.getDouble("buy-price", -1))
                    .build();

                map.put(key.toUpperCase(Locale.ROOT), worth);
            } catch (Exception e) {
                log.warning("Failed to parse item '" + key + "': " + e.getMessage());
            }
        }

        itemWorthMap = Collections.unmodifiableMap(map);
        log.info("Loaded " + map.size() + " item prices from items.yml");
    }

    /**
     * Parses shop.yml into a list of ShopSections.
     * Must be called after parseItems() so sell prices are available for markup computation.
     *
     * Buy price resolution order:
     *   1. Explicit buy-price in shop.yml  → used as-is
     *   2. Sell price from items.yml × shop.buy-price-markup
     *   3. No sell price and no explicit buy-price → item skipped with a warning
     */
    private void parseShop() {
        File shopFile = new File(plugin.getDataFolder(), "shop.yml");
        if (!shopFile.exists()) {
            log.warning("shop.yml not found — admin shop will be empty.");
            shopSections = List.of();
            return;
        }

        FileConfiguration cfg = YamlConfiguration.loadConfiguration(shopFile);
        ConfigurationSection sectionsNode = cfg.getConfigurationSection("sections");
        if (sectionsNode == null) {
            log.warning("shop.yml has no 'sections' node — admin shop will be empty.");
            shopSections = List.of();
            return;
        }

        List<ShopSection> sections = new ArrayList<>();

        for (String sectionKey : sectionsNode.getKeys(false)) {
            ConfigurationSection sec = sectionsNode.getConfigurationSection(sectionKey);
            if (sec == null) continue;

            String displayName = sec.getString("display-name", sectionKey);

            Material icon = Material.matchMaterial(sec.getString("icon", "CHEST"));
            if (icon == null) {
                log.warning("shop.yml section '" + sectionKey + "' has invalid icon — using CHEST.");
                icon = Material.CHEST;
            }

            List<ShopEntry> entries = new ArrayList<>();
            for (Map<?, ?> itemMap : sec.getMapList("items")) {
                String matStr = String.valueOf(itemMap.get("material")).toUpperCase(Locale.ROOT);
                Material mat  = Material.matchMaterial(matStr);
                if (mat == null) {
                    log.warning("shop.yml [" + sectionKey + "]: unknown material '" + matStr + "' — skipped.");
                    continue;
                }

                String name = itemMap.containsKey("display-name")
                    ? String.valueOf(itemMap.get("display-name"))
                    : matStr.replace('_', ' ');

                int qty = itemMap.containsKey("quantity")
                    ? ((Number) itemMap.get("quantity")).intValue()
                    : 1;

                double buyPrice;
                if (itemMap.containsKey("buy-price")) {
                    buyPrice = ((Number) itemMap.get("buy-price")).doubleValue();
                } else {
                    ItemWorth worth = itemWorthMap.get(matStr);
                    if (worth == null) {
                        log.warning("shop.yml [" + sectionKey + "]: " + matStr
                            + " has no sell price in items.yml and no explicit buy-price — skipped.");
                        continue;
                    }
                    // Round to 2 decimal places so prices are clean numbers
                    buyPrice = Math.round(worth.getBasePrice() * shopBuyPriceMarkup * 100.0) / 100.0;
                }

                entries.add(new ShopEntry(mat, name, qty, buyPrice));
            }

            if (!entries.isEmpty()) {
                sections.add(new ShopSection(displayName, icon, entries));
            }
        }

        shopSections = Collections.unmodifiableList(sections);
        log.info("Loaded " + sections.size() + " shop sections from shop.yml");
    }

    private void saveDefault(String resourceName) {
        File file = new File(plugin.getDataFolder(), resourceName);
        if (!file.exists()) plugin.saveResource(resourceName, false);
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    public Map<String, ItemWorth> getItemWorthMap()  { return itemWorthMap; }
    public List<ShopSection>      getShopSections()  { return shopSections; }
    public double getShopBuyPriceMarkup()            { return shopBuyPriceMarkup; }
    public double getShopConfirmAbove()              { return shopConfirmAbove; }

    public boolean isMysql()             { return "MYSQL".equals(dbType); }
    public String  getSqliteFile()       { return sqliteFile; }
    public String  getMysqlHost()        { return mysqlHost; }
    public int     getMysqlPort()        { return mysqlPort; }
    public String  getMysqlDatabase()    { return mysqlDatabase; }
    public String  getMysqlUsername()    { return mysqlUsername; }
    public String  getMysqlPassword()    { return mysqlPassword; }
    public int     getMysqlPoolSize()    { return mysqlPoolSize; }
    public long    getTxBatchIntervalMs(){ return txBatchIntervalMs; }

    public boolean isWorthTooltipEnabled()        { return worthTooltipEnabled; }
    public String  getWorthTooltipFormat()        { return worthTooltipFormat; }
    public String  getWorthTooltipUnsellableFormat() { return worthTooltipUnsellableFormat; }
    public boolean isWorthTooltipShowUnsellable() { return worthTooltipShowUnsellable; }
    public boolean isWorthTooltipCompact()        { return worthTooltipCompact; }
    public long    getWorthTooltipRefreshDelayTicks() { return worthTooltipRefreshDelayTicks; }
    public Set<InventoryType> getWorthTooltipIgnoredTypes() { return worthTooltipIgnoredTypes; }

    public String  getCurrencySymbol()     { return currencySymbol; }
    public String  getCurrencyName()       { return currencyName; }
    public String  getCurrencyNamePlural() { return currencyNamePlural; }
    public int     getDecimalPlaces()      { return decimalPlaces; }
    public boolean isSkipHotbar()          { return skipHotbar; }
    public double  getBreakdownThreshold() { return breakdownThreshold; }

    public int     getPlayerShopMaxListings()     { return playerShopMaxListings; }

    public boolean isMarketEnabled()              { return marketEnabled; }
    public long    getMarketTickIntervalSeconds()  { return marketTickIntervalSeconds; }
    public long    getMarketRollingWindowHours()   { return marketRollingWindowHours; }
    public double  getMarketBaselineVolume()       { return marketBaselineVolume; }
    public double  getMarketDefaultVolatility()    { return marketDefaultVolatility; }
    public double  getMarketDefaultRecovery()      { return marketDefaultRecovery; }
    public boolean isMarketAdaptiveDepthEnabled()  { return marketAdaptiveDepthEnabled; }
    public double  getMarketBaselinePerSeller()    { return marketBaselinePerSeller; }
    public int     getMarketMinParticipants()      { return marketMinParticipants; }
    public boolean isMarketCircuitBreakerEnabled() { return marketCircuitBreakerEnabled; }
    public double  getMarketMaxDailyDrop()         { return marketMaxDailyDrop; }
}
