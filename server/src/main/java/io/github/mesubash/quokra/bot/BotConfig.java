package io.github.mesubash.quokra.bot;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public record BotConfig(
    String greeting,
    // Optional short line shown next to the chat button before the chat is opened.
    String teaser,
    List<MenuItem> menu,
    Human human,
    Urgent urgent,
    String fallback,
    int maxFallbacks,
    String handoffMessage) {

    // Option id that always means "talk to a person".
    public static final String HUMAN_OPTION = "human";

    public record MenuItem(String id, String title, List<String> keywords, String answer) {}

    public record Human(String title, List<String> keywords) {}

    public record Urgent(List<String> keywords, String message) {}

    public record Option(String id, String title) {}

    // Buttons shown under the greeting, answers and fallbacks.
    public List<Option> options() {
        var options = new ArrayList<Option>();
        menu.forEach(item -> options.add(new Option(item.id(), item.title())));
        options.add(new Option(HUMAN_OPTION, human.title()));
        return List.copyOf(options);
    }

    public Optional<String> optionTitle(String optionId) {
        if (HUMAN_OPTION.equals(optionId)) {
            return Optional.of(human.title());
        }
        return menu.stream().filter(item -> item.id().equals(optionId)).map(MenuItem::title).findFirst();
    }
}
