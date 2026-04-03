package com.incidentplatform.ingestion.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.shared.events.SourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WazuhNormalizer")
class WazuhNormalizerTest {

    private WazuhNormalizer normalizer;
    private ObjectMapper objectMapper;
    private static final String TENANT_ID = "test-tenant";

    @BeforeEach
    void setUp() {
        normalizer = new WazuhNormalizer();
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("should normalize valid Wazuh alert")
    void shouldNormalizeValidWazuhAlert() throws Exception {
        // given
        final JsonNode payload = objectMapper.readTree("""
                {
                  "timestamp": "2024-01-15T10:30:00.000+0000",
                  "rule": {
                    "id": "5551",
                    "level": 12,
                    "description": "Multiple authentication failures",
                    "groups": ["authentication_failures", "syslog"]
                  },
                  "agent": {
                    "id": "003",
                    "name": "web-server-01",
                    "ip": "192.168.1.10"
                  },
                  "data": {
                    "srcip": "10.0.0.1"
                  }
                }
                """);

        // when
        final NormalizationResult result = normalizer.normalize(payload, TENANT_ID);

        // then
        assertThat(result.firingAlerts()).hasSize(1);
        assertThat(result.resolvedAlerts()).isEmpty();

        final var alert = result.firingAlerts().get(0);
        assertThat(alert.tenantId()).isEqualTo(TENANT_ID);
        assertThat(alert.source()).isEqualTo("wazuh");
        assertThat(alert.sourceType()).isEqualTo(SourceType.SECURITY);
        assertThat(alert.severity()).isEqualTo("CRITICAL");
        assertThat(alert.title()).contains("Multiple authentication failures");
        assertThat(alert.title()).contains("web-server-01");
        assertThat(alert.description()).isEqualTo("Multiple authentication failures");
        assertThat(alert.fingerprint()).isEqualTo("wazuh:5551:003");
    }

    @Nested
    @DisplayName("Severity mapping from rule.level")
    class SeverityMapping {

        @Test
        @DisplayName("level 0-3 should map to LOW")
        void levelZeroToThreeShouldBeLow() throws Exception {
            assertThat(normalizeSeverityFromLevel(0)).isEqualTo("LOW");
            assertThat(normalizeSeverityFromLevel(3)).isEqualTo("LOW");
        }

        @Test
        @DisplayName("level 4-7 should map to MEDIUM")
        void levelFourToSevenShouldBeMedium() throws Exception {
            assertThat(normalizeSeverityFromLevel(4)).isEqualTo("MEDIUM");
            assertThat(normalizeSeverityFromLevel(7)).isEqualTo("MEDIUM");
        }

        @Test
        @DisplayName("level 8-11 should map to HIGH")
        void levelEightToElevenShouldBeHigh() throws Exception {
            assertThat(normalizeSeverityFromLevel(8)).isEqualTo("HIGH");
            assertThat(normalizeSeverityFromLevel(11)).isEqualTo("HIGH");
        }

        @Test
        @DisplayName("level 12-15 should map to CRITICAL")
        void levelTwelveToFifteenShouldBeCritical() throws Exception {
            assertThat(normalizeSeverityFromLevel(12)).isEqualTo("CRITICAL");
            assertThat(normalizeSeverityFromLevel(15)).isEqualTo("CRITICAL");
        }

        @Test
        @DisplayName("missing level should default to LOW")
        void missingLevelShouldDefaultToLow() throws Exception {
            // given
            final JsonNode payload = objectMapper.readTree("""
                    {
                      "rule": {
                        "id": "5551",
                        "description": "Test alert"
                      },
                      "agent": { "id": "001", "name": "agent-1" }
                    }
                    """);

            // when
            final NormalizationResult result = normalizer.normalize(payload, TENANT_ID);

            // then
            assertThat(result.firingAlerts().get(0).severity()).isEqualTo("LOW");
        }

        private String normalizeSeverityFromLevel(int level) throws Exception {
            final JsonNode payload = objectMapper.readTree(String.format("""
                    {
                      "rule": {
                        "id": "5551",
                        "level": %d,
                        "description": "Test alert"
                      },
                      "agent": { "id": "001", "name": "agent-1" }
                    }
                    """, level));
            return normalizer.normalize(payload, TENANT_ID)
                    .firingAlerts().get(0).severity();
        }
    }

    @Nested
    @DisplayName("Metadata")
    class Metadata {

        @Test
        @DisplayName("should include rule_id, agent_name, agent_id in metadata")
        void shouldIncludeBasicMetadata() throws Exception {
            // given
            final JsonNode payload = buildValidPayload("5551", 12,
                    "Multiple auth failures", "003", "web-server-01");

            // when
            final var metadata = normalizer.normalize(payload, TENANT_ID)
                    .firingAlerts().get(0).metadata();

            // then
            assertThat(metadata).containsEntry("rule_id", "5551");
            assertThat(metadata).containsEntry("agent_id", "003");
            assertThat(metadata).containsEntry("agent_name", "web-server-01");
        }

        @Test
        @DisplayName("should include rule_groups when present")
        void shouldIncludeRuleGroups() throws Exception {
            // given
            final JsonNode payload = objectMapper.readTree("""
                    {
                      "rule": {
                        "id": "5551",
                        "level": 12,
                        "description": "Test",
                        "groups": ["auth_failures", "syslog"]
                      },
                      "agent": { "id": "001", "name": "agent-1" }
                    }
                    """);

            // when
            final var metadata = normalizer.normalize(payload, TENANT_ID)
                    .firingAlerts().get(0).metadata();

            // then
            assertThat(metadata).containsKey("rule_groups");
            assertThat(metadata.get("rule_groups"))
                    .contains("auth_failures")
                    .contains("syslog");
        }

        @Test
        @DisplayName("should include source_ip when present in data")
        void shouldIncludeSourceIp() throws Exception {
            // given
            final JsonNode payload = objectMapper.readTree("""
                    {
                      "rule": { "id": "5551", "level": 12, "description": "Test" },
                      "agent": { "id": "001", "name": "agent-1" },
                      "data": { "srcip": "192.168.1.100" }
                    }
                    """);

            // when
            final var metadata = normalizer.normalize(payload, TENANT_ID)
                    .firingAlerts().get(0).metadata();

            // then
            assertThat(metadata).containsEntry("source_ip", "192.168.1.100");
        }

        @Test
        @DisplayName("should NOT include full_log in metadata (security)")
        void shouldNotIncludeFullLog() throws Exception {
            // given
            final JsonNode payload = objectMapper.readTree("""
                    {
                      "rule": { "id": "5551", "level": 12, "description": "Test" },
                      "agent": { "id": "001", "name": "agent-1" },
                      "full_log": "password=secret123 user=admin"
                    }
                    """);

            // when
            final var metadata = normalizer.normalize(payload, TENANT_ID)
                    .firingAlerts().get(0).metadata();

            // then
            assertThat(metadata).doesNotContainKey("full_log");
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("should throw NormalizationException when rule section missing")
        void shouldThrowWhenRuleMissing() throws Exception {
            // given
            final JsonNode payload = objectMapper.readTree("""
                    {
                      "timestamp": "2024-01-15T10:30:00.000+0000",
                      "agent": { "id": "001", "name": "agent-1" }
                    }
                    """);

            // then
            assertThatThrownBy(() -> normalizer.normalize(payload, TENANT_ID))
                    .isInstanceOf(NormalizationException.class)
                    .hasMessageContaining("rule");
        }

        @Test
        @DisplayName("should throw when rule.id is missing")
        void shouldThrowWhenRuleIdMissing() throws Exception {
            // given
            final JsonNode payload = objectMapper.readTree("""
                    {
                      "rule": {
                        "level": 12,
                        "description": "Test"
                      },
                      "agent": { "id": "001", "name": "agent-1" }
                    }
                    """);

            // then
            assertThatThrownBy(() -> normalizer.normalize(payload, TENANT_ID))
                    .isInstanceOf(NormalizationException.class)
                    .hasMessageContaining("id");
        }
    }

    @Test
    @DisplayName("should handle missing agent section gracefully")
    void shouldHandleMissingAgent() throws Exception {
        // given
        final JsonNode payload = objectMapper.readTree("""
                {
                  "rule": {
                    "id": "5551",
                    "level": 8,
                    "description": "Test alert"
                  }
                }
                """);

        // when
        final NormalizationResult result = normalizer.normalize(payload, TENANT_ID);

        // then
        assertThat(result.firingAlerts()).hasSize(1);
        assertThat(result.firingAlerts().get(0).title()).contains("unknown");
    }

    @Test
    @DisplayName("getSourceName should return wazuh")
    void shouldReturnSourceName() {
        assertThat(normalizer.getSourceName()).isEqualTo("wazuh");
    }

    @Test
    @DisplayName("fingerprint should be ruleId:agentId")
    void shouldBuildCorrectFingerprint() throws Exception {
        // given
        final JsonNode payload = buildValidPayload(
                "9999", 12, "Test", "007", "server-007");

        // when
        final String fingerprint = normalizer.normalize(payload, TENANT_ID)
                .firingAlerts().get(0).fingerprint();

        // then
        assertThat(fingerprint).isEqualTo("wazuh:9999:007");
    }

    private JsonNode buildValidPayload(String ruleId, int level,
                                       String description,
                                       String agentId, String agentName)
            throws Exception {
        return objectMapper.readTree(String.format("""
                {
                  "rule": {
                    "id": "%s",
                    "level": %d,
                    "description": "%s"
                  },
                  "agent": {
                    "id": "%s",
                    "name": "%s"
                  }
                }
                """, ruleId, level, description, agentId, agentName));
    }
}