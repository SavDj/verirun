package app.verirun.entity;

import app.verirun.dto.VerilatorOptions;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class VerilatorOptionsConverter implements AttributeConverter<VerilatorOptions, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(VerilatorOptions options) {
        if (options == null) return null;
        try {
            return MAPPER.writeValueAsString(options);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize VerilatorOptions", e);
        }
    }

    @Override
    public VerilatorOptions convertToEntityAttribute(String json) {
        if (json == null || json.isBlank()) return VerilatorOptions.defaults();
        try {
            return MAPPER.readValue(json, VerilatorOptions.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize VerilatorOptions", e);
        }
    }
}
