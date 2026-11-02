package io.github.mesubash.quokra.conversation;

import io.github.mesubash.quokra.conversation.Message.Source;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Converter
class SourcesConverter implements AttributeConverter<List<Source>, String> {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<Source>> TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<Source> sources) {
        return sources == null || sources.isEmpty() ? null : JSON.writeValueAsString(sources);
    }

    @Override
    public List<Source> convertToEntityAttribute(String json) {
        return json == null ? null : JSON.readValue(json, TYPE);
    }
}
