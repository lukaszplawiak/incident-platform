package com.incidentplatform.auth.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;
import java.util.List;

/**
 * JPA converter between {@code List<String>} and comma-separated {@code TEXT}.
 *
 * <p>Stores scopes as: {@code "incidents:read,incidents:write,alerts:ingest"}
 *
 * <p>Why comma-separated TEXT instead of PostgreSQL text[]:
 * Hibernate's {@code @Convert} with {@code columnDefinition = "text[]"} causes
 * a {@code ClassCastException} at SessionFactory initialization time
 * (CustomMutabilityConvertedBasicTypeImpl cannot be cast to BasicPluralType).
 * Comma-separated TEXT is simpler, portable, and sufficient for scope storage
 * since scopes never contain commas.
 */
@Converter
public class ScopesConverter
        implements AttributeConverter<List<String>, String> {

    private static final String DELIMITER = ",";

    @Override
    public String convertToDatabaseColumn(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return "";
        }
        return String.join(DELIMITER, scopes);
    }

    @Override
    public List<String> convertToEntityAttribute(String dbValue) {
        if (dbValue == null || dbValue.isBlank()) {
            return List.of();
        }
        return Arrays.stream(dbValue.split(DELIMITER))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}