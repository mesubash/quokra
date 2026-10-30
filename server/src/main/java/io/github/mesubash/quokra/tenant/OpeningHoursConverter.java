package io.github.mesubash.quokra.tenant;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.databind.json.JsonMapper;

@Converter
public class OpeningHoursConverter implements AttributeConverter<OpeningHours, String> {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public String convertToDatabaseColumn(OpeningHours hours) {
        return hours == null ? null : JSON.writeValueAsString(hours);
    }

    @Override
    public OpeningHours convertToEntityAttribute(String json) {
        return json == null ? null : JSON.readValue(json, OpeningHours.class);
    }
}
