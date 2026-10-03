package com.incidentplatform.shared.security;

import java.util.regex.Pattern;

/**
 * The one format of a tenant id on the platform (backlog #0-92): a slug of
 * 3-63 characters, {@code [a-z0-9-]}, no hyphen at either end (a DNS label).
 *
 * <h2>Changed (backlog #0-92): one format, checked wherever a tenant enters</h2>
 * Until #0-92 only a tenant created through the platform API had to be a slug
 * (backlog #0-80, then in auth-service); everywhere else a tenant id was a
 * free-form string, and a service token's only rule was "1-100 characters, no
 * whitespace or control characters" ({@code JwtUtils}, backlog #0-11). A
 * Kafka record's {@code X-Tenant-Id} header was checked for nothing but
 * blankness, so a producer could put line breaks into every log line of the
 * record's processing and a new series into a metric per value. The platform
 * has no data to keep (it is in development), so the slug became the format
 * everywhere: it holds nothing a log line, a header, a record key, a metric tag
 * or a URL needs escaped.
 *
 * <p>Checked where a tenant id enters: a {@code CHECK} on every table with a
 * {@code tenant_id}, in every service (auth-service V28 and one migration per
 * service), tokens when issued and when read
 * ({@code JwtUtils}, {@code JwtAuthFilter}), the {@code X-Tenant-Id} header of
 * auth-service's public endpoints, {@link TenantContext#set}, every Kafka
 * record a service builds ({@code TenantRecords}) and every record a service
 * consumes ({@code TenantKafkaRecordResolver}).
 */
public final class TenantIds {

    /** 3-63 characters, [a-z0-9-], no hyphen at either end (a DNS label). */
    public static final String SLUG = "^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$";

    private static final Pattern PATTERN = Pattern.compile(SLUG);

    /** The rule, for error messages; never the rejected value. */
    public static final String RULE = "a tenant id is 3-63 characters of a-z, 0-9 and '-', "
            + "starting and ending with a letter or digit";

    private TenantIds() {
    }

    public static boolean isValid(String tenantId) {
        return tenantId != null && PATTERN.matcher(tenantId).matches();
    }

    /**
     * @return {@code tenantId}, when it is a valid tenant id
     * @throws InvalidTenantIdException otherwise (null and blank included), with
     *         a message that does not echo the rejected value (it may itself
     *         hold the characters the rule keeps out of logs)
     */
    public static String requireValid(String tenantId) {
        if (!isValid(tenantId)) {
            throw new InvalidTenantIdException();
        }
        return tenantId;
    }
}
