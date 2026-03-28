package com.incidentplatform.ingestion_normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.incidentplatform.shared.dto.UnifiedAlertDto;
import com.incidentplatform.shared.events.SourceType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class WazuhNormalizer extends BaseNormalizer {

    private static final String SOURCE = "wazuh";

    @Override
    public NormalizationResult normalize(JsonNode rawPayload, String tenantId) {
        log.debug("Normalizing Wazuh alert for tenant: {}", tenantId);

        final JsonNode rule = rawPayload.get("rule");
        if (isMissingOrNotObject(rule)) {
            throw new NormalizationException(SOURCE,
                    "Missing 'rule' section in Wazuh payload");
        }

        final String ruleId = getTextOrThrow(rule, "id");
        final String ruleDescription = getTextOrThrow(rule, "description");
        final int level = getRuleLevel(rule);
        final String severity = mapSeverity(level);

        final JsonNode agent = rawPayload.get("agent");
        final String agentName = getText(agent, "name", "unknown");
        final String agentId = getText(agent, "id", "unknown");

        final String title = String.format("[Wazuh] %s on %s",
                ruleDescription, agentName);
        final Instant firedAt = parseInstant(getText(rawPayload, "timestamp", null));
        final Map<String, String> metadata = buildMetadata(
                ruleId, agentName, agentId, rule, rawPayload);

        log.info("Wazuh alert normalized: ruleId={}, level={}, severity={}, " +
                "agent={}, tenant={}", ruleId, level, severity, agentName, tenantId);

        return NormalizationResult.firingOnly(List.of(new UnifiedAlertDto(
                UUID.randomUUID(),
                tenantId,
                SOURCE,
                SourceType.SECURITY,
                severity,
                title,
                ruleDescription,
                firedAt,
                metadata
        )));
    }

    @Override
    public String getSourceName() {
        return SOURCE;
    }

    private String mapSeverity(int level) {
        if (level >= 12) return "CRITICAL";
        if (level >= 8)  return "HIGH";
        if (level >= 4)  return "MEDIUM";
        return "LOW";
    }

    private int getRuleLevel(JsonNode rule) {
        final JsonNode levelNode = rule.get("level");
        if (levelNode == null || !levelNode.isNumber()) {
            log.warn("Missing or invalid 'rule.level' in Wazuh payload, " +
                    "defaulting to 0");
            return 0;
        }
        return levelNode.asInt();
    }

    private Map<String, String> buildMetadata(String ruleId,
                                              String agentName,
                                              String agentId,
                                              JsonNode rule,
                                              JsonNode payload) {
        final Map<String, String> metadata = new HashMap<>();
        metadata.put("rule_id", ruleId);
        metadata.put("agent_name", agentName);
        metadata.put("agent_id", agentId);

        final JsonNode groups = rule.get("groups");
        if (groups != null && groups.isArray()) {
            final StringBuilder sb = new StringBuilder();
            groups.forEach(g -> {
                if (sb.length() > 0) sb.append(",");
                sb.append(g.asText());
            });
            metadata.put("rule_groups", sb.toString());
        }

        final JsonNode data = payload.get("data");
        if (data != null) {
            final String srcIp = getText(data, "srcip", null);
            if (srcIp != null) metadata.put("source_ip", srcIp);
        }

        return metadata;
    }
}