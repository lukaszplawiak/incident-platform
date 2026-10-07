package com.incidentplatform.auth.api;

import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.exception.GlobalExceptionHandler;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A write refused by a foreign key to {@code tenants} (backlog #0-82, review):
 * 403, not the shared 500; any other integrity error keeps the shared answer.
 * The shared {@link GlobalExceptionHandler} is registered first on purpose, as
 * in {@link TenantStatusBusyHandlerTest}: the test fails if
 * {@link UnrecordedTenantHandler} loses its precedence.
 */
@DisplayName("UnrecordedTenantHandler")
class UnrecordedTenantHandlerTest {

    @RestController
    static class WritingController {
        @PutMapping("/api/v1/tenant/settings")
        void unrecordedTenant() {
            throw violation("fk_tenant_settings_tenant");
        }

        @PutMapping("/api/v1/teams")
        void otherConstraint() {
            throw violation("uq_teams_name_tenant");
        }

        @PutMapping("/api/v1/users")
        void noConstraintName() {
            throw new DataIntegrityViolationException("could not execute statement",
                    new SQLException("insert into users values ('secret')"));
        }

        private static DataIntegrityViolationException violation(String constraint) {
            // What Spring makes of PostgreSQL's 23503/23505 on a JPA write.
            return new DataIntegrityViolationException("could not execute statement [insert ... 'secret']",
                    new ConstraintViolationException("could not execute statement",
                            new SQLException("insert into t values ('secret')", "23503"), constraint));
        }
    }

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new WritingController())
            .setControllerAdvice(new GlobalExceptionHandler(), new UnrecordedTenantHandler())
            .build();

    @Test
    @DisplayName("a tenant foreign key is 403 FORBIDDEN, and the SQL stays out of the body")
    void tenantForeignKeyIs403() throws Exception {
        mockMvc.perform(put("/api/v1/tenant/settings"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.FORBIDDEN))
                .andExpect(jsonPath("$.message").value("You do not have permission to perform this action."))
                .andExpect(content().string(not(containsString("secret"))))
                .andExpect(content().string(not(containsString("tenant"))));
    }

    @Test
    @DisplayName("the constraint is found deeper in the cause chain and whatever its case; a nameless one is none")
    void constraintMatching() {
        final DataIntegrityViolationException nested = new DataIntegrityViolationException("outer",
                new RuntimeException("wrapper", new ConstraintViolationException("inner",
                        new SQLException("x", "23503"), "FK_USERS_TENANT")));
        assertThat(UnrecordedTenantHandler.tenantForeignKey(nested)).contains("fk_users_tenant");

        final DataIntegrityViolationException nameless = new DataIntegrityViolationException("outer",
                new ConstraintViolationException("inner", new SQLException("x", "23503"), null));
        assertThat(UnrecordedTenantHandler.tenantForeignKey(nameless)).isEmpty();
    }

    @Test
    @DisplayName("another constraint, or none named, gets the shared 500 as before")
    void otherIntegrityErrorsUnchanged() throws Exception {
        for (final String path : new String[] {"/api/v1/teams", "/api/v1/users"}) {
            mockMvc.perform(put(path))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INTERNAL_SERVER_ERROR))
                    .andExpect(content().string(not(containsString("secret"))));
        }
    }
}
