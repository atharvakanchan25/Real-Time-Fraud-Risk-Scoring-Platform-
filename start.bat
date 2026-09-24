@echo off
setlocal

set ROOT=%~dp0
set LOGS=%ROOT%logs
if not exist "%LOGS%" mkdir "%LOGS%"

:: ── JVM args passed to every service ─────────────────────────────────────────
set DB_ARGS=-Dspring.datasource.url=jdbc:postgresql://localhost:5432/frauddb -Dspring.datasource.username=fraud -Dspring.datasource.password=fraud
set KAFKA_ARGS=-Dspring.kafka.bootstrap-servers=localhost:9092
set AUTH_ARGS=-Drules.analyst.user=analyst -Drules.analyst.password=analyst -Dcase.analyst.user=analyst1 -Dcase.analyst.password=analyst1pass -Dcase.admin.user=admin1 -Dcase.admin.password=admin1pass
set JVM=%DB_ARGS% %KAFKA_ARGS% %AUTH_ARGS%

:: ── 1. Infrastructure ────────────────────────────────────────────────────────
echo [1/3] Starting infrastructure...
docker compose -f "%ROOT%docker-compose.yml" up -d
if errorlevel 1 ( echo ERROR: docker compose failed & pause & exit /b 1 )

:: ── 2. Wait for Kafka ────────────────────────────────────────────────────────
echo [2/3] Waiting for Kafka...
:wait_kafka
docker exec kafka /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >nul 2>&1
if errorlevel 1 ( timeout /t 3 /nobreak >nul & goto wait_kafka )
echo Kafka ready.

:: ── 3. Kafka topics ──────────────────────────────────────────────────────────
for %%t in (payment-events decision-events final-decisions payment-events-dlq feedback-events) do (
    docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic %%t --partitions 6 --replication-factor 1 >nul 2>&1
)
echo Topics ready.

:: ── 4. Microservices ─────────────────────────────────────────────────────────
echo [3/3] Starting microservices...
start "ingestion-service"       cmd /c "java %JVM% -jar "%ROOT%ingestion-service\target\ingestion-service-0.0.1-SNAPSHOT.jar" > "%LOGS%\ingestion.log" 2>&1"
start "enrichment-service"      cmd /c "java %JVM% -jar "%ROOT%enrichment-service\target\enrichment-service-0.0.1-SNAPSHOT.jar" > "%LOGS%\enrichment.log" 2>&1"
start "rules-service"           cmd /c "java %JVM% -jar "%ROOT%rules-service\target\rules-service-0.0.1-SNAPSHOT.jar" > "%LOGS%\rules.log" 2>&1"
start "scoring-service"         cmd /c "java %JVM% -jar "%ROOT%scoring-service\target\scoring-service-0.0.1-SNAPSHOT.jar" > "%LOGS%\scoring.log" 2>&1"
start "decision-service"        cmd /c "java %JVM% -jar "%ROOT%decision-service\target\decision-service-0.0.1-SNAPSHOT.jar" > "%LOGS%\decision.log" 2>&1"
start "case-management-service" cmd /c "java %JVM% -jar "%ROOT%case-management-service\target\case-management-service-0.0.1-SNAPSHOT.jar" > "%LOGS%\case-management.log" 2>&1"
start "feedback-service"        cmd /c "java %JVM% -jar "%ROOT%feedback-service\target\feedback-service-0.0.1-SNAPSHOT.jar" > "%LOGS%\feedback.log" 2>&1"

:: ── 5. Health checks ─────────────────────────────────────────────────────────
echo Waiting 45s for services to boot...
timeout /t 45 /nobreak >nul

echo.
echo -- Health checks --
call :check ingestion-service       8081
call :check enrichment-service      8082
call :check rules-service           8083
call :check scoring-service         8084
call :check decision-service        8085
call :check case-management-service 8086
call :check feedback-service        8087

echo.
echo -- Endpoints --
echo   Ingestion API : http://localhost:8081
echo   Grafana       : http://localhost:3000  (admin / admin)
echo   Prometheus    : http://localhost:9090
echo   Jaeger UI     : http://localhost:16686
echo   Logs          : %LOGS%
echo.
endlocal
goto :eof

:check
curl -sf http://localhost:%2/actuator/health >nul 2>&1
if errorlevel 1 ( echo   [FAIL] %1 :%2 ) else ( echo   [ OK ] %1 :%2 )
goto :eof
