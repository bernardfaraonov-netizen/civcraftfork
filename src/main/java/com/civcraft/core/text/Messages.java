package com.civcraft.core.text;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * All player facing text lives in {@code lang/<locale>.yml} as MiniMessage strings. Server owners can
 * override any key by editing the copy in the plugin folder; missing keys fall back to the bundled file.
 */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static Messages instance;

    private final Map<String, String> strings = new HashMap<>();
    private final Logger logger;
    private Component prefix = Component.empty();
    private TagResolver styles = TagResolver.empty();

    private Messages(Logger logger) {
        this.logger = logger;
    }

    public static Messages load(Plugin plugin, String locale) {
        Messages messages = new Messages(plugin.getLogger());
        messages.plugin = plugin;
        messages.locale = locale;
        String path = "lang/" + locale + ".yml";
        try (InputStream in = plugin.getResource(path)) {
            if (in != null) {
                messages.read(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            } else {
                plugin.getLogger().warning("Unknown locale " + locale + ", falling back to ru_RU");
                try (InputStream ru = plugin.getResource("lang/ru_RU.yml")) {
                    messages.read(YamlConfiguration.loadConfiguration(new InputStreamReader(ru, StandardCharsets.UTF_8)));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read bundled language file", e);
        }
        File override = new File(plugin.getDataFolder(), path);
        if (override.isFile()) {
            messages.read(YamlConfiguration.loadConfiguration(override));
        }
        messages.prefix = MM.deserialize(messages.strings.getOrDefault("prefix", ""));
        instance = messages;
        return messages;
    }

    public static Messages get() {
        return instance;
    }

    private Plugin plugin;
    private String locale;

    /**
     * Adds a module language file {@code lang/<locale>/<module>.yml} (bundled defaults, then the copy in
     * the plugin folder). Modules call this from {@code load()} so their keys never conflict.
     */
    public void include(String module) {
        String path = "lang/" + locale + "/" + module + ".yml";
        try (InputStream in = plugin.getResource(path)) {
            if (in == null) {
                try (InputStream ru = plugin.getResource("lang/ru_RU/" + module + ".yml")) {
                    if (ru != null) read(YamlConfiguration.loadConfiguration(new InputStreamReader(ru, StandardCharsets.UTF_8)));
                }
            } else {
                read(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read " + path, e);
        }
        File override = new File(plugin.getDataFolder(), path);
        if (override.isFile()) read(YamlConfiguration.loadConfiguration(override));
    }

    private void read(ConfigurationSection section) {
        for (String key : section.getKeys(true)) {
            if (section.isConfigurationSection(key)) continue;
            if (section.isList(key)) {
                strings.put(key, String.join("<newline>", section.getStringList(key)));
            } else {
                strings.put(key, section.getString(key, ""));
            }
        }
    }

    public boolean has(String key) {
        return strings.containsKey(key);
    }

    public String raw(String key) {
        String value = strings.get(key);
        if (value == null) {
            logger.warning("Missing message key: " + key);
            return key;
        }
        return value;
    }

    public Component component(String key, TagResolver... resolvers) {
        return MM.deserialize(raw(key), TagResolver.resolver(TagResolver.resolver(resolvers), styles));
    }

    /** Parses an arbitrary MiniMessage string with the configured styles available. */
    public Component parse(String miniMessage, TagResolver... resolvers) {
        return MM.deserialize(miniMessage, TagResolver.resolver(TagResolver.resolver(resolvers), styles));
    }

    public List<Component> lines(String key, TagResolver... resolvers) {
        String value = raw(key);
        return java.util.Arrays.stream(value.split("<newline>")).map(s -> parse(s, resolvers)).toList();
    }

    public String plain(String key, TagResolver... resolvers) {
        return PlainTextComponentSerializer.plainText().serialize(component(key, resolvers));
    }

    public void send(Audience audience, String key, TagResolver... resolvers) {
        audience.sendMessage(prefix.append(component(key, resolvers)));
    }

    public void sendRaw(Audience audience, String key, TagResolver... resolvers) {
        audience.sendMessage(component(key, resolvers));
    }

    public void actionBar(Audience audience, String key, TagResolver... resolvers) {
        audience.sendActionBar(component(key, resolvers));
    }

    public Component prefix() {
        return prefix;
    }

    // --- placeholder helpers -------------------------------------------------------------------

    public static TagResolver arg(String name, Object value) {
        return Placeholder.unparsed(name, String.valueOf(value));
    }

    public static TagResolver arg(String name, Component value) {
        return Placeholder.component(name, value);
    }

    public static TagResolver money(String name, long cents) {
        return Placeholder.unparsed(name, com.civcraft.core.util.Money.format(cents));
    }

    public static TagResolver number(String name, double value) {
        return Placeholder.unparsed(name, Format.number(value));
    }
}
