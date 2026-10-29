package io.github.mesubash.quokra.bot;

import io.github.mesubash.quokra.bot.BotConfig.MenuItem;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

// Decides how the bot answers one customer message. No database or network access.
public final class BotEngine {

    public sealed interface Decision {}

    public record Answer(String text) implements Decision {}

    public record Fallback(String text) implements Decision {}

    public record Handoff(Reason reason, String text) implements Decision {}

    public enum Reason { CUSTOMER_REQUEST, URGENT, FALLBACK_LIMIT }

    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern MARKS = Pattern.compile("\\p{M}");

    private BotEngine() {}

    // fallbackCount: misses in a row before this message.
    public static Decision decide(BotConfig config, String text, String optionId, int fallbackCount) {
        String words = words(text);

        // Urgent keywords always win, so they're checked first.
        if (containsAny(words, config.urgent().keywords())) {
            return new Handoff(Reason.URGENT, config.urgent().message());
        }
        if (BotConfig.HUMAN_OPTION.equals(optionId) || containsAny(words, config.human().keywords())) {
            return new Handoff(Reason.CUSTOMER_REQUEST, config.handoffMessage());
        }
        if (optionId != null) {
            for (MenuItem item : config.menu()) {
                if (item.id().equals(optionId)) {
                    return new Answer(item.answer());
                }
            }
        }

        MenuItem best = null;
        int bestHits = 0;
        for (MenuItem item : config.menu()) {
            int hits = countMatches(words, item.keywords());
            if (hits > bestHits) {
                best = item;
                bestHits = hits;
            }
        }
        if (best != null) {
            return new Answer(best.answer());
        }

        if (fallbackCount + 1 >= config.maxFallbacks()) {
            return new Handoff(Reason.FALLBACK_LIMIT, config.handoffMessage());
        }
        return new Fallback(config.fallback());
    }

    private static boolean containsAny(String words, List<String> keywords) {
        return countMatches(words, keywords) > 0;
    }

    private static int countMatches(String words, List<String> keywords) {
        int hits = 0;
        for (String keyword : keywords) {
            String phrase = words(keyword);
            if (!phrase.isBlank() && words.contains(phrase)) {
                hits++;
            }
        }
        return hits;
    }

    // Lowercase, no accents, words separated by single spaces, padded so " word " matches whole words.
    static String words(String text) {
        if (text == null) {
            return " ";
        }
        String plain = MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
        return " " + NOT_WORD.matcher(plain.toLowerCase(Locale.ROOT)).replaceAll(" ").strip() + " ";
    }
}
