package dev.smpeconomy.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Message lookup and sending. Text lives in messages/<locale>.yml as MiniMessage.
 *
 * An instance is bound to one locale; forRecipient() gives you a view for someone's
 * language. Missing keys fall back per key, so a partial translation still works.
 */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String DIRECTORY = "messages";
    private static final String LEGACY_FILE = "messages.yml";

    private final JavaPlugin plugin;
    private final MessageRegistry registry;
    private final MessageValidator validator = new MessageValidator();
    // shared with every view so a reload is picked up everywhere
    private final Map<Locale, MessageCatalog> catalogs;
    private final Locale bound;

    private Locale defaultLocale = Locale.ENGLISH;
    private boolean perPlayerLocale = true;
    private boolean strict;

    public Messages(JavaPlugin plugin, MessageRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        this.catalogs = new LinkedHashMap<>();
        this.bound = null;
        reload();
    }

    private Messages(Messages source, Locale bound) {
        this.plugin = source.plugin;
        this.registry = source.registry;
        this.catalogs = source.catalogs;
        this.bound = bound;
        this.defaultLocale = source.defaultLocale;
        this.perPlayerLocale = source.perPlayerLocale;
        this.strict = source.strict;
    }

    /** View bound to this recipient's language. */
    public Messages forRecipient(CommandSender to) {
        return new Messages(this, localeOf(to));
    }

    // ── Loading ──────────────────────────────────────────────────────────────

    public void reload() {
        this.strict = plugin.getConfig().getBoolean("messages.strict", false);
        this.perPlayerLocale = plugin.getConfig().getBoolean("messages.per-player-locale", true);
        this.defaultLocale = parseLocale(plugin.getConfig().getString("messages.default-locale", "en"));

        File directory = new File(plugin.getDataFolder(), DIRECTORY);
        migrateLegacyFile(directory);
        if (!directory.isDirectory()) {
            plugin.saveResource(DIRECTORY + "/en.yml", false);
        }

        catalogs.clear();
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".yml"));
        for (File file : files == null ? new File[0] : files) {
            String tag = file.getName().substring(0, file.getName().length() - 4);
            Locale locale = parseLocale(tag);
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            if (locale.equals(defaultLocale)) {
                mergeJarDefaults(yaml, file);
            }
            catalogs.put(locale, MessageCatalog.of(locale, toPlainMap(yaml)));
        }

        MessageCatalog fallback = catalogs.get(defaultLocale);
        if (fallback == null) {
            plugin.getLogger().severe("No messages file for the default locale '"
                    + defaultLocale.toLanguageTag() + "'; every message will fall back to its key");
            return;
        }
        report(validator.validate(registry, fallback));
    }

    // keep edits from servers still on the old single-file layout
    private void migrateLegacyFile(File directory) {
        File legacy = new File(plugin.getDataFolder(), LEGACY_FILE);
        if (!legacy.isFile() || directory.isDirectory()) {
            return;
        }
        File target = new File(directory, defaultLocale.getLanguage() + ".yml");
        try {
            Files.createDirectories(directory.toPath());
            Files.move(legacy.toPath(), target.toPath());
            plugin.getLogger().info("Moved " + LEGACY_FILE + " to " + DIRECTORY + "/"
                    + target.getName() + " for per-language messages");
        } catch (IOException e) {
            plugin.getLogger().warning("Could not migrate " + LEGACY_FILE + ": " + e.getMessage());
        }
    }

    // pull in keys added by an update without clobbering local edits
    private void mergeJarDefaults(YamlConfiguration yaml, File file) {
        try (InputStream in = plugin.getResource(DIRECTORY + "/en.yml")) {
            if (in == null) {
                return;
            }
            yaml.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8)));
            yaml.options().copyDefaults(true);
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not sync message defaults: " + e.getMessage());
        }
    }

    // Hand MessageCatalog a nested map, not dotted keys. getValues(true) would flatten
    // plural blocks into separate one/other entries and the block would be lost.
    static Map<String, Object> toPlainMap(ConfigurationSection section) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : section.getValues(false).entrySet()) {
            Object value = entry.getValue();
            out.put(entry.getKey(), value instanceof ConfigurationSection child
                    ? toPlainMap(child)
                    : value);
        }
        return out;
    }

    private static Locale parseLocale(String tag) {
        Locale locale = Locale.forLanguageTag(tag.replace('_', '-'));
        return locale.getLanguage().isEmpty() ? Locale.ENGLISH : locale;
    }

    private void report(MessageValidator.Report result) {
        result.warnings().forEach(w -> plugin.getLogger().warning("messages: " + w));
        result.errors().forEach(e -> plugin.getLogger().severe("messages: " + e));
        if (!result.ok()) {
            String summary = "messages has " + result.errors().size() + " error(s)";
            if (strict) {
                throw new IllegalStateException(summary + " and messages.strict is on");
            }
            plugin.getLogger().severe(summary + "; affected messages fall back to their key");
        }
    }

    // ── Locale resolution ────────────────────────────────────────────────────

    private Locale localeOf(CommandSender to) {
        if (!perPlayerLocale || !(to instanceof Player player)) {
            return defaultLocale;
        }
        return player.locale();
    }

    // exact locale, then language, then the server default
    private MessageCatalog catalogFor(Locale locale) {
        Locale target = locale == null ? defaultLocale : locale;
        MessageCatalog exact = catalogs.get(target);
        if (exact != null) {
            return exact;
        }
        MessageCatalog byLanguage = catalogs.get(Locale.of(target.getLanguage()));
        if (byLanguage != null) {
            return byLanguage;
        }
        return catalogs.get(defaultLocale);
    }

    private String lookup(MessageKey key, TokenBag tokens, Locale locale) {
        MessageCatalog catalog = catalogFor(locale);
        String text = null;
        if (catalog != null) {
            text = key.plural()
                    ? catalog.plural(key.path(), countOf(key, tokens)).orElse(null)
                    : catalog.string(key.path()).orElse(null);
        }
        if (text == null && catalog != catalogFor(defaultLocale)) {
            MessageCatalog base = catalogFor(defaultLocale);
            if (base != null) {
                text = key.plural()
                        ? base.plural(key.path(), countOf(key, tokens)).orElse(null)
                        : base.string(key.path()).orElse(null);
            }
        }
        return text;
    }

    // ── Resolution ───────────────────────────────────────────────────────────

    public Component get(MessageKey key) {
        return get(key, TokenBag.empty());
    }

    public Component get(MessageKey key, TokenBag tokens) {
        return MM.deserialize(raw(key, tokens));
    }

    public String raw(MessageKey key) {
        return raw(key, TokenBag.empty());
    }

    public String raw(MessageKey key, TokenBag tokens) {
        checkTokens(key, tokens);
        String text = lookup(key, tokens, bound);
        if (text == null) {
            plugin.getLogger().warning("No message configured for " + key.path());
            return key.path();
        }
        return tokens.applyTo(text);
    }

    /** Lore line. Italic is forced off — MC italicises lore and MiniMessage leaves it unset. */
    public Component lore(MessageKey key) {
        return lore(key, TokenBag.empty());
    }

    public Component lore(MessageKey key, TokenBag tokens) {
        return get(key, tokens).decoration(TextDecoration.ITALIC, false);
    }

    /** Multi-line lore, stored as a YAML list so a translation can use a different number of lines. */
    public List<Component> loreList(MessageKey key) {
        return loreList(key, TokenBag.empty());
    }

    public List<Component> loreList(MessageKey key, TokenBag tokens) {
        checkTokens(key, tokens);
        MessageCatalog catalog = catalogFor(bound);
        List<String> lines = catalog == null ? List.of() : catalog.list(key.path()).orElse(List.of());
        if (lines.isEmpty()) {
            MessageCatalog base = catalogFor(defaultLocale);
            lines = base == null ? List.of() : base.list(key.path()).orElse(List.of());
        }
        if (lines.isEmpty()) {
            plugin.getLogger().warning("No lore configured for " + key.path());
            return List.of();
        }
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(MM.deserialize(tokens.applyTo(line)).decoration(TextDecoration.ITALIC, false));
        }
        return out;
    }

    // ── Sending ──────────────────────────────────────────────────────────────

    public void send(CommandSender to, MessageKey key) {
        send(to, key, TokenBag.empty());
    }

    public void send(CommandSender to, MessageKey key, TokenBag tokens) {
        to.sendMessage(forRecipient(to).get(key, tokens));
    }

    public void sendPrefixed(CommandSender to, MessageKey key) {
        sendPrefixed(to, key, TokenBag.empty());
    }

    public void sendPrefixed(CommandSender to, MessageKey key, TokenBag tokens) {
        Messages view = forRecipient(to);
        to.sendMessage(view.get(CoreKeys.PREFIX).append(view.get(key, tokens)));
    }

    public void sendActionBar(Player to, MessageKey key) {
        sendActionBar(to, key, TokenBag.empty());
    }

    public void sendActionBar(Player to, MessageKey key, TokenBag tokens) {
        to.sendActionBar(forRecipient(to).get(key, tokens));
    }

    // ── Checks ───────────────────────────────────────────────────────────────

    // wrong tokens are a caller bug, so treat them like a broken file
    private void checkTokens(MessageKey key, TokenBag tokens) {
        Set<String> declared = key.declaredTokens();
        Set<String> supplied = tokens.names();
        if (declared.equals(supplied)) {
            return;
        }
        List<String> problems = new ArrayList<>();
        supplied.stream().filter(n -> !declared.contains(n))
                .forEach(n -> problems.add("unexpected {" + n + "}"));
        declared.stream().filter(n -> !supplied.contains(n))
                .forEach(n -> problems.add("missing {" + n + "}"));
        String detail = key.path() + " — " + String.join(", ", problems);
        if (strict) {
            throw new IllegalArgumentException("Message tokens: " + detail);
        }
        plugin.getLogger().warning("Message tokens: " + detail);
    }

    private long countOf(MessageKey key, TokenBag tokens) {
        String token = key.countToken();
        String value = token == null ? null : tokens.asMap().get(token);
        try {
            return value == null ? 0L : Long.parseLong(value.replaceAll("[^-0-9]", ""));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
