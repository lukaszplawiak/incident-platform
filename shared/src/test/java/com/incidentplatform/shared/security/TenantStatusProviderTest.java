package com.incidentplatform.shared.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The interface's defaults, used by a provider that reads its own database (auth-service) or a test's lambda. */
@DisplayName("TenantStatusProvider defaults")
class TenantStatusProviderTest {

    private final TenantStatusProvider ownDatabase = tenantId -> "acme".equals(tenantId)
            ? TenantAccess.READ_ONLY : TenantAccess.FULL;

    @Test
    @DisplayName("knownAccessOf and confirmedStateOf answer as accessOf: such a provider always has the answer, "
            + "without a suspension time")
    void defaultsAnswerAsAccessOf() {
        assertThat(ownDatabase.knownAccessOf("acme")).isEqualTo(TenantAccess.READ_ONLY);
        assertThat(ownDatabase.confirmedStateOf("acme")).contains(new TenantAccessState(TenantAccess.READ_ONLY, null));
        assertThat(ownDatabase.confirmedStateOf("globex")).contains(new TenantAccessState(TenantAccess.FULL, null));
    }
}
