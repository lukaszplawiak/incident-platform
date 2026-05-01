# syntax=docker/dockerfile:1.4
# ---- Build stage ----
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

COPY mvnw mvnw.cmd ./
COPY .mvn .mvn

COPY pom.xml ./
COPY shared/pom.xml shared/
COPY ingestion-service/pom.xml ingestion-service/
COPY incident-service/pom.xml incident-service/
COPY notification-service/pom.xml notification-service/
COPY escalation-service/pom.xml escalation-service/
COPY postmortem-service/pom.xml postmortem-service/
COPY oncall-service/pom.xml oncall-service/

RUN --mount=type=cache,target=/root/.m2,id=escalation-service-m2 \
    ./mvnw dependency:go-offline -pl shared,escalation-service -am -q --no-transfer-progress

COPY shared/src shared/src
COPY escalation-service/src escalation-service/src

RUN --mount=type=cache,target=/root/.m2,id=escalation-service-m2 \
    ./mvnw package -pl shared,escalation-service -am \
    -DskipTests --no-transfer-progress

FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

COPY --from=builder /build/escalation-service/target/escalation-service-*.jar app.jar

RUN chown appuser:appgroup app.jar

USER appuser

EXPOSE 8084

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]