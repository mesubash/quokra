package io.github.mesubash.quokra.bot;

import io.github.mesubash.quokra.tenant.Tenant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

// Which bot a tenant uses: the one edited in the Admin tab, or else its bot file.
@Component
public class TenantBots {

    static final int MAX_LENGTH = 200_000;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static class InvalidBot extends RuntimeException {
        InvalidBot(String message) {
            super(message);
        }
    }

    private record Parsed(String source, BotConfig config) {}

    private final BotConfigRegistry files;
    // Parsed once per edit, not on every message.
    private final Map<UUID, Parsed> custom = new ConcurrentHashMap<>();

    public TenantBots(BotConfigRegistry files) {
        this.files = files;
    }

    public Optional<BotConfig> forTenant(Tenant tenant) {
        String source = tenant.getBotCustom();
        if (source == null) {
            return files.find(tenant.getBotConfig());
        }
        Parsed parsed = custom.get(tenant.getId());
        if (parsed == null || !parsed.source().equals(source)) {
            parsed = new Parsed(source, BotConfigParser.parse("bot", source));
            custom.put(tenant.getId(), parsed);
        }
        return Optional.of(parsed.config());
    }

    // Checks an edited bot with the same rules as the files, and returns it tidied up as JSON to store.
    public String validate(Map<String, Object> fields) {
        String json = JSON.writeValueAsString(fields);
        if (json.length() > MAX_LENGTH) {
            throw new InvalidBot("bot: too long (at most " + MAX_LENGTH + " characters)");
        }
        try {
            return JSON.writeValueAsString(fields(BotConfigParser.parse("bot", json)));
        } catch (BotConfigParser.InvalidBotConfig e) {
            throw new InvalidBot(e.getMessage());
        }
    }

    // The tenant's bot in the shape of a bot file, for the editor.
    public Optional<Map<String, Object>> fieldsFor(Tenant tenant) {
        return forTenant(tenant).map(TenantBots::fields);
    }

    static Map<String, Object> fields(BotConfig bot) {
        var map = new LinkedHashMap<String, Object>();
        map.put("greeting", bot.greeting());
        if (bot.teaser() != null) {
            map.put("teaser", bot.teaser());
        }
        map.put("menu", bot.menu().stream().map(item -> Map.of("id", item.id(), "title", item.title(),
            "keywords", item.keywords(), "answer", item.answer())).toList());
        map.put("human", Map.of("title", bot.human().title(), "keywords", bot.human().keywords()));
        map.put("urgent", Map.of("keywords", bot.urgent().keywords(), "message", bot.urgent().message()));
        map.put("fallback", bot.fallback());
        map.put("max_fallbacks", bot.maxFallbacks());
        map.put("handoff_message", bot.handoffMessage());
        BotConfig.Texts t = bot.texts();
        var texts = new LinkedHashMap<String, Object>();
        texts.put("online", t.online());
        texts.put("waiting", t.waiting());
        texts.put("closed", t.closed());
        texts.put("placeholder", t.placeholder());
        texts.put("ask_email", t.askEmail());
        texts.put("bad_email", t.badEmail());
        texts.put("ask_name", t.askName());
        texts.put("skip", t.skip());
        texts.put("auto_closed", t.autoClosed());
        texts.put("offline", t.offline());
        texts.put("offline_handoff", t.offlineHandoff());
        map.put("texts", texts);
        return map;
    }
}
