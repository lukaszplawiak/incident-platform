# ============================================================
# Incident Platform — Makefile
# Użycie: make <komenda>
# ============================================================

.PHONY: help dev-up dev-down dev-logs dev-reset grafana-password-check build test clean

# Domyślna komenda — pokaż pomoc
help:
	@echo ""
	@echo "  Incident Platform — dostępne komendy:"
	@echo ""
	@echo "  Środowisko lokalne:"
	@echo "    make dev-up       — uruchom cały stack docker-compose (infrastruktura, monitoring,"
	@echo "                        logi: Loki/Alloy/proxy Dockera, serwisy); wymaga docker/.env"
	@echo "                        z DB_PASSWORD, POSTGRES_ADMIN_PASSWORD i GRAFANA_ADMIN_PASSWORD"
	@echo "    make dev-down     — zatrzymaj kontenery (też wymaga haseł w docker/.env)"
	@echo "    make grafana-password-check — czy Grafana nadal przyjmuje admin/admin"
	@echo "    make dev-reset    — zatrzymaj + usuń volumes (czysta baza)"
	@echo "    make dev-logs     — logi wszystkich kontenerów"
	@echo "    make kafka-ui     — otwórz Kafka UI w przeglądarce"
	@echo ""
	@echo "  Build i testy:"
	@echo "    make build        — zbuduj wszystkie moduły (skip tests)"
	@echo "    make test         — uruchom wszystkie testy"
	@echo "    make test-unit    — tylko testy jednostkowe (szybkie)"
	@echo "    make clean        — wyczyść target/ we wszystkich modułach"
	@echo ""
	@echo "  Poszczególne serwisy:"
	@echo "    make run-ingestion   — uruchom ingestion-service lokalnie"
	@echo "    make run-incident    — uruchom incident-service lokalnie"
	@echo ""

# ============================================================
# Środowisko lokalne (Docker Compose)
# ============================================================

dev-up:
	@echo "► Uruchamiam środowisko lokalne..."
	docker compose -f docker/docker-compose.yml up -d
	@echo "✓ Środowisko działa:"
	@echo "  PostgreSQL : localhost:5432"
	@echo "  Redis      : localhost:6379"
	@echo "  Kafka      : localhost:9092"
	@echo "  Kafka UI   : http://localhost:8090"
	@echo "  pgAdmin    : http://localhost:5050"
	@echo "  Grafana    : http://localhost:3000 (logi: Explore -> Loki)"
	@$(MAKE) --no-print-directory grafana-password-check

# Backlog #0-94 step 2: ostrzega o słabym haśle Grafany (czyta logi wszystkich
# tenantów) i o wolumenie sprzed GRAFANA_ADMIN_PASSWORD, który ma nadal
# admin/admin. Szczegóły i powody: docker/grafana-password-check.sh.
grafana-password-check:
	@docker/grafana-password-check.sh

dev-down:
	@echo "► Zatrzymuję środowisko..."
	docker compose -f docker/docker-compose.yml down
	@echo "✓ Zatrzymano"

dev-reset:
	@echo "► Resetuję środowisko (usuwa dane!)..."
	docker compose -f docker/docker-compose.yml down -v
	@echo "✓ Środowisko wyczyszczone"

dev-logs:
	docker compose -f docker/docker-compose.yml logs -f

kafka-ui:
	open http://localhost:8090 || xdg-open http://localhost:8090

# ============================================================
# Build i testy
# ============================================================

build:
	./mvnw clean package -DskipTests

test:
	./mvnw test

test-unit:
	./mvnw test -Dgroups="unit"

clean:
	./mvnw clean

# ============================================================
# Uruchamianie serwisów lokalnie (wymaga dev-up)
# ============================================================

run-ingestion:
	./mvnw spring-boot:run -pl ingestion-service \
		-Dspring-boot.run.profiles=local

run-incident:
	./mvnw spring-boot:run -pl incident-service \
		-Dspring-boot.run.profiles=local

run-escalation:
	./mvnw spring-boot:run -pl escalation-service \
		-Dspring-boot.run.profiles=local

run-notification:
	./mvnw spring-boot:run -pl notification-service \
		-Dspring-boot.run.profiles=local

run-postmortem:
	./mvnw spring-boot:run -pl postmortem-service \
		-Dspring-boot.run.profiles=local
