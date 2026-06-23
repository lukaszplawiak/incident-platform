package com.incidentplatform.shared.dto;

import java.util.List;

/**
 * Stable HTTP response wrapper for paginated collections.
 *
 * <h2>Why not return Spring's {@code Page<T>} directly from controllers</h2>
 * Spring Data's {@code PageImpl} serialises to JSON including its full
 * internal structure: {@code pageable.offset}, {@code pageable.paged},
 * {@code pageable.unpaged}, {@code sort.sorted}, {@code sort.unsorted} etc.
 * These are implementation details — they change between Spring Boot versions
 * and no API client needs them. Returning {@code Page<T>} directly from a
 * controller creates tight coupling between the wire format and the Spring
 * Data version in use.
 *
 * <h2>What this provides instead</h2>
 * A minimal, stable set of pagination metadata that any client needs:
 * <ul>
 *   <li>{@code content} — the items on this page</li>
 *   <li>{@code page} — zero-based current page number</li>
 *   <li>{@code size} — requested page size</li>
 *   <li>{@code totalElements} — total matching records (from COUNT query)</li>
 *   <li>{@code totalPages} — total number of pages</li>
 *   <li>{@code first} / {@code last} — boundary indicators</li>
 * </ul>
 *
 * <h2>Usage in controllers</h2>
 * <pre>{@code
 * // The service returns Page<T> from Spring Data — unchanged.
 * // The controller converts it to PagedResponse before returning:
 * Page<IncidentDto> page = queryService.findAll(tenantId, filter, pageable);
 * return ResponseEntity.ok(PagedResponse.of(page.getContent(),
 *         page.getNumber(), page.getSize(), page.getTotalElements(),
 *         page.getTotalPages(), page.isFirst(), page.isLast()));
 * }</pre>
 *
 * <h2>Why no Spring Data dependency here</h2>
 * {@code shared} is a plain JAR without {@code spring-data-commons} on its
 * classpath. Accepting a {@code Page<T>} parameter here would require adding
 * that dependency to {@code shared} solely to support a convenience method —
 * unnecessary coupling for a pure value type. Each controller already has
 * {@code Page<T>} available through its own {@code spring-boot-starter-data-jpa}
 * dependency and can extract the fields directly.
 *
 * @param <T> the type of items in the page
 */
public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    /**
     * Convenience factory — avoids repeating the 7 field names at every
     * call site while keeping {@code PagedResponse} free of any Spring Data
     * dependency.
     *
     * <p>Typical controller usage:
     * <pre>{@code
     * final Page<IncidentDto> page = queryService.findAll(tenantId, filter, pageable);
     * return ResponseEntity.ok(PagedResponse.of(page));
     * }</pre>
     *
     * <p>The overload accepting raw fields is available for tests or other
     * callers that don't have a {@code Page<T>} instance:
     * <pre>{@code
     * PagedResponse.of(List.of(dto), 0, 20, 1L, 1, true, true)
     * }</pre>
     */
    public static <T> PagedResponse<T> of(List<T> content,
                                          int page,
                                          int size,
                                          long totalElements,
                                          int totalPages,
                                          boolean first,
                                          boolean last) {
        return new PagedResponse<>(
                content, page, size, totalElements, totalPages, first, last);
    }
}