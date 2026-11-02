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
    // false: while the bot is answering, customers pick an option instead of typing (the chat still asks
    // for email and name, and people can type once someone of the team joins).
    boolean freeText,
    Texts texts) {

    // Option id that always means "talk to a person".
    public static final String HUMAN_OPTION = "human";
    // Option id that shows the main topics again, after an answer with follow-ups.
    public static final String MENU_OPTION = "menu";
    static final String MENU_TITLE = "← Main topics";

    // followUps: ids of the topics offered as buttons after this answer (none: the main topics, as before).
    // inMenu: false keeps a topic out of the main topics; it's still found by its keywords and as a follow-up.
    public record MenuItem(String id, String title, List<String> keywords, String answer, List<String> followUps,
                           boolean inMenu) {

        public MenuItem(String id, String title, List<String> keywords, String answer) {
            this(id, title, keywords, answer, List.of(), true);
        }
    }

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

    // The main topics: buttons under the greeting and fallbacks, and after answers without follow-ups.
    public List<Option> options() {
        var options = new ArrayList<Option>();
        menu.stream().filter(MenuItem::inMenu).forEach(item -> options.add(new Option(item.id(), item.title())));
        options.add(new Option(HUMAN_OPTION, human.title()));
        return List.copyOf(options);
    }

    // Buttons under an answer: its follow-ups, a way back to the main topics, and a person.
    public List<Option> optionsAfter(MenuItem answered) {
        if (answered.followUps().isEmpty()) {
            return options();
        }
        var options = new ArrayList<Option>();
        answered.followUps().forEach(id -> find(id).ifPresent(item -> options.add(new Option(item.id(), item.title()))));
        options.add(new Option(MENU_OPTION, MENU_TITLE));
        options.add(new Option(HUMAN_OPTION, human.title()));
        return List.copyOf(options);
    }

    public Optional<MenuItem> find(String id) {
        return menu.stream().filter(item -> item.id().equals(id)).findFirst();
    }

    public Optional<String> optionTitle(String optionId) {
        if (HUMAN_OPTION.equals(optionId)) {
            return Optional.of(human.title());
        }
        if (MENU_OPTION.equals(optionId)) {
            return Optional.of(MENU_TITLE);
        }
        return menu.stream().filter(item -> item.id().equals(optionId)).map(MenuItem::title).findFirst();
    }
}
