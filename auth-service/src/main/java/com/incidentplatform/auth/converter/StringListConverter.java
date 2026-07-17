package com.incidentplatform.auth.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;
import java.util.List;

/**
 * JPA converter between {@code List<String>} and PostgreSQL {@code TEXT[]}.
 *
 * <h2>Why not hypersistence-utils ListArrayType</h2>
 * {@code ListArrayType} from hypersistence-utils was deprecated in v3.14.1
 * (December 2025). This converter uses standard JPA {@link AttributeConverter}
 * — zero external dependencies, compatible with any Hibernate version.
 *
 * <h2>PostgreSQL array queries</h2>
 * The stored {@code text[]} column supports native array operators:
 * <pre>
 *   -- Find keys with a specific scope
 *   SELECT * FROM api_keys WHERE 'incidents:read' = ANY(scopes);
 * </pre>
 */
@Converter
public class StringListConverter
        implements AttributeConverter<List<String>, String[]> {

    @Override
    public String[] convertToDatabaseColumn(List<String> list) {
        if (list == null || list.isEmpty()) {
            return new String[0];
        }
        return list.toArray(new String[0]);
    }

    @Override
    public List<String> convertToEntityAttribute(String[] array) {
        if (array == null || array.length == 0) {
            return List.of();
        }
        return List.of(array);
    }
}