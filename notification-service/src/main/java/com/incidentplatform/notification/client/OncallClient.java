package com.incidentplatform.notification.client;

import java.util.Optional;

public interface OncallClient {

    Optional<OncallInfo> getCurrentOncall(String tenantId, String role);

    Optional<OncallInfo> findBySlackUserId(String slackUserId);

    record OncallInfo(
            String userId,
            String userName,
            String email,
            String phone,
            String slackUserId,
            String role
    ) {
        public boolean hasDm()  { return slackUserId != null && !slackUserId.isBlank(); }
        public boolean hasSms() { return phone != null && !phone.isBlank(); }
    }
}