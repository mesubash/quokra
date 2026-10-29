package io.github.mesubash.quokra.conversation;

import io.github.mesubash.quokra.bot.BotConfig.Option;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Converter
class OptionsConverter implements AttributeConverter<List<Option>, String> {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<Option>> TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<Option> options) {
        return options == null || options.isEmpty() ? null : JSON.writeValueAsString(options);
    }

    @Override
    public List<Option> convertToEntityAttribute(String json) {
        return json == null ? null : JSON.readValue(json, TYPE);
    }
}
