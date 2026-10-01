package com.incidentplatform.postmortem.api;

import com.incidentplatform.postmortem.config.OpenApiConfig;
import com.incidentplatform.postmortem.config.SecurityConfig;
import com.incidentplatform.postmortem.service.PostmortemService;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.events.IncidentEventKafkaSender;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.ServiceTokenProvider;
import com.incidentplatform.shared.security.UnauthorizedEntryPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The OpenAPI document of postmortem-service is generated from
 * {@link PostmortemController}'s annotations and served without a token.
 *
 * <p>Until springdoc was added, the controller's {@code @Operation} annotations
 * were compiled against the annotations jar alone and nothing served them. This
 * test fails if the dependency, {@link OpenApiConfig} or the
 * {@code PUBLIC_PATHS} permit in {@link SecurityConfig} goes missing. That the
 * document is public in every profile is backlog #0-73; when that is fixed,
 * this test moves to the profile that keeps it.
 *
 * <p>{@code @WebMvcTest} does not load springdoc's auto-configuration, so the
 * three classes that build and serve the document are imported explicitly.
 * The rest of the context comes from {@link PostmortemApiTestApplication}.
 */
@WebMvcTest(PostmortemController.class)
@Import({SecurityConfig.class, UnauthorizedEntryPoint.class, OpenApiConfig.class})
@ImportAutoConfiguration({
        SpringDocConfiguration.class,
        SpringDocConfigProperties.class,
        SpringDocWebMvcConfiguration.class
})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding",
        "jwt.access-token-ttl=PT15M",
        "jwt.service-token-ttl=PT1H",
        "spring.application.name=postmortem-service"
})
@DisplayName("postmortem-service OpenAPI document")
class OpenApiDocumentTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PostmortemService postmortemService;

    @MockitoBean
    private JwtUtils jwtUtils;

    @MockitoBean
    private ServiceTokenProvider serviceTokenProvider;

    @MockitoBean
    private AuditEventPublisher auditEventPublisher;

    @MockitoBean
    private IncidentEventKafkaSender incidentEventKafkaSender;

    @Test
    @DisplayName("GET /v3/api-docs without a token describes the postmortem endpoints")
    void apiDocs_describesPostmortemEndpoints() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title")
                        .value("Incident Platform — Postmortem Service"))
                .andExpect(jsonPath("$.paths['/api/v1/postmortems'].get.summary")
                        .value("List postmortems for the current tenant (paginated)"))
                .andExpect(jsonPath("$.paths['/api/v1/postmortems/incident/{incidentId}/review'].post")
                        .exists())
                .andExpect(jsonPath("$.components.securitySchemes['Bearer Authentication'].scheme")
                        .value("bearer"));
    }
}
