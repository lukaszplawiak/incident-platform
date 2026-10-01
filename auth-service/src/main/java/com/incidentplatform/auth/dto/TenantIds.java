package com.incidentplatform.auth.dto;

/** The format of a tenant id created through the platform API (backlog #0-80). */
public final class TenantIds {

    /** 3-63 characters, [a-z0-9-], no hyphen at either end (a DNS label). */
    public static final String SLUG = "^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$";

    private TenantIds() {
    }
}
