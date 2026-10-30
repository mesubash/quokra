package io.github.mesubash.quokra.bot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

// Loads bot files (.yml or .yaml) once at startup: the built-in ones, then QUOKRA_BOTS_DIR (same name wins).
@Slf4j
@Component
public class BotConfigRegistry {

    private static final List<String> SUFFIXES = List.of(".yml", ".yaml");

    private final Map<String, BotConfig> configs;

    public BotConfigRegistry(@Value("${quokra.bots.dir:}") String botsDir) throws IOException {
        Map<String, BotConfig> loaded = new HashMap<>();
        var resolver = new PathMatchingResourcePatternResolver();
        for (String suffix : SUFFIXES) {
            for (Resource resource : resolver.getResources("classpath*:bots/*" + suffix)) {
                String file = resource.getFilename();
                loaded.put(name(file), BotConfigParser.parse(file, resource.getContentAsString(StandardCharsets.UTF_8)));
            }
        }
        if (!botsDir.isBlank()) {
            try (Stream<Path> files = Files.list(Path.of(botsDir))) {
                for (Path path : files.filter(p -> name(p.toString()) != null).toList()) {
                    String file = path.getFileName().toString();
                    loaded.put(name(file), BotConfigParser.parse(path.toString(), Files.readString(path)));
                }
            }
        }
        this.configs = Map.copyOf(loaded);
        log.info("Loaded bot configs: {}", configs.keySet());
    }

    public List<String> names() {
        return configs.keySet().stream().sorted().toList();
    }

    public Optional<BotConfig> find(String name) {
        return Optional.ofNullable(configs.get(name));
    }

    // File name without the .yml/.yaml ending, or null for other files.
    private static String name(String file) {
        return SUFFIXES.stream().filter(file::endsWith)
            .map(suffix -> file.substring(0, file.length() - suffix.length()))
            .findFirst().orElse(null);
    }
}
