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
    String handoffMessage,
    Texts texts) {

    // Option id that always means "talk to a person".
    public static final String HUMAN_OPTION = "human";

    public record MenuItem(String id, String title, List<String> keywords, String answer) {}

    public record Human(String title, List<String> keywords) {}

    public record Urgent(List<String> keywords, String message) {}

    public record Option(String id, String title) {}

    // Wording a tenant can change; anything missing from the file keeps these defaults.
    // placeholder may contain {name}: the company's name, or the agent's once one has joined.
    // offline shows in the header outside opening hours; offlineHandoff replaces the handoff message then,
    // with {opens} as when the team is back.
    public record Texts(String online, String waiting, String closed, String placeholder,
                        String askEmail, String badEmail, String askName, String skip, String autoClosed,
                        String offline, String offlineHandoff) {

        public static final Texts DEFAULT = new Texts(
            "Online · replies instantly",
            "Connecting you with our team…",
            "Conversation closed · write again any time",
            "Reply to {name}…",
            "Before I connect you, what's your email? Our team will use it to follow up if you step away.",
            "That doesn't look like an email address. Could you check it?",
            "Thanks! And what's your name?",
            "Skip",
            "We've closed this chat since we haven't heard back. Write again any time.",
            "Away now · we'll reply when we're back",
            "We're away right now and back {opens}. Leave your message here and we'll reply then.");
    }

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
