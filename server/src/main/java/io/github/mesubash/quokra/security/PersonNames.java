package io.github.mesubash.quokra.security;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

// Agent names as customers see them: never an email address. "sam.lee99@example.test" reads as "Sam Lee".
final class PersonNames {

    static final String FALLBACK = "Support";

    private PersonNames() {
    }

    // A name from the part of an email before the @: split on . _ - +, digits dropped, words capitalised.
    static String fromEmail(String email) {
        String local = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
        return Arrays.stream(local.split("[._+\\-]+"))
            .map(word -> word.replaceAll("[0-9]", ""))
            .filter(word -> !word.isBlank())
            .map(word -> word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1).toLowerCase(Locale.ROOT))
            .collect(Collectors.joining(" "));
    }

    // Anything that looks like an email becomes a name; blanks become the fallback.
    static String safe(String name) {
        String value = name == null ? "" : name.strip();
        if (value.contains("@")) {
            value = fromEmail(value);
        }
        return value.isBlank() ? FALLBACK : value;
    }

    static String first(String name) {
        return safe(name).split("\\s+")[0];
    }
}
