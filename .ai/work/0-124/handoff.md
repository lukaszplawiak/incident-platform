# Handoff: 0-124

## Changed
- notification-service application.yml: spring.mail.properties.mail.smtp.starttls.required: true; mail comment rewritten.
- docker/docker-compose.yml: notification-service gets SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED "false".
- k8s/overlays/dev/kustomization.yml: comment only (stale sentence removed).
- README.md Infrastructure Hardening: one bullet.
- New test SmtpTlsDefaultsTest (config package).

## How to verify
- `./mvnw test -pl notification-service -am -Dtest=SmtpTlsDefaultsTest -Dsurefire.failIfNoSpecifiedTests=false` - default and dev-override bindings.

## Tests changed and why
- none

## Deliberately out of scope
- nothing

## Noticed, not touched
- none

## Follow-up needed

## Disputed
