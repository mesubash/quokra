package io.github.mesubash.quokra.bot;

import io.github.mesubash.quokra.bot.BotConfig.Human;
import io.github.mesubash.quokra.bot.BotConfig.MenuItem;
import io.github.mesubash.quokra.bot.BotConfig.Urgent;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

// Turns a bot YAML file into a BotConfig, with errors that name the file and field.
final class BotConfigParser {

    static class InvalidBotConfig extends RuntimeException {
        InvalidBotConfig(String message) {
            super(message);
        }
    }

    private final String file;

    private BotConfigParser(String file) {
        this.file = file;
    }

    static BotConfig parse(String file, String yaml) {
        Object root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        } catch (RuntimeException e) {
            throw new InvalidBotConfig(file + ": not valid YAML (" + e.getMessage() + ")");
        }
        return new BotConfigParser(file).config(root);
    }

    private BotConfig config(Object root) {
        Map<String, Object> map = map(root, "the file");
        List<MenuItem> menu = list(map.get("menu"), "menu").stream()
            .map(this::menuItem)
            .toList();
        if (menu.isEmpty()) {
            throw error("menu needs at least one item");
        }
        var ids = new HashSet<String>();
        for (MenuItem item : menu) {
            if (item.id().equals(BotConfig.HUMAN_OPTION)) {
                throw error("menu id '" + BotConfig.HUMAN_OPTION + "' is reserved");
            }
            if (!ids.add(item.id())) {
                throw error("menu id '" + item.id() + "' is used twice");
            }
        }

        Map<String, Object> human = map(map.get("human"), "human");
        Map<String, Object> urgent = map(map.get("urgent"), "urgent");
        int maxFallbacks = map.get("max_fallbacks") instanceof Integer n ? n : -1;
        if (maxFallbacks < 1) {
            throw error("max_fallbacks must be a number of at least 1");
        }

        return new BotConfig(
            text(map, "greeting"),
            optionalText(map, "teaser"),
            menu,
            new Human(text(human, "human.title"), keywords(human.get("keywords"), "human.keywords")),
            new Urgent(keywords(urgent.get("keywords"), "urgent.keywords"), text(urgent, "urgent.message")),
            text(map, "fallback"),
            maxFallbacks,
            text(map, "handoff_message"));
    }

    private MenuItem menuItem(Object item) {
        Map<String, Object> map = map(item, "menu item");
        String id = text(map, "menu.id");
        return new MenuItem(id, text(map, "menu[" + id + "].title"),
            keywords(map.get("keywords"), "menu[" + id + "].keywords"), text(map, "menu[" + id + "].answer"));
    }

    private List<String> keywords(Object value, String field) {
        List<String> keywords = list(value, field).stream()
            .map(k -> k instanceof String s && !s.isBlank() ? s : null)
            .toList();
        if (keywords.contains(null)) {
            throw error(field + " must only contain words");
        }
        return keywords;
    }

    private String text(Map<String, Object> map, String field) {
        String key = field.substring(field.lastIndexOf('.') + 1);
        if (map.get(key) instanceof String s && !s.isBlank()) {
            return s.strip();
        }
        throw error(field + " is required");
    }

    private String optionalText(Map<String, Object> map, String field) {
        Object value = map.get(field);
        if (value == null) {
            return null;
        }
        if (value instanceof String s && !s.isBlank()) {
            return s.strip();
        }
        throw error(field + " must be text");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value, String field) {
        if (value instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw error(field + " must be a section with fields");
    }

    private List<Object> list(Object value, String field) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> l) {
            return List.copyOf(l);
        }
        throw error(field + " must be a list");
    }

    private InvalidBotConfig error(String message) {
        return new InvalidBotConfig(file + ": " + message);
    }
}
