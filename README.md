# LUDO-BINGE-BACKEND

Authoritative online backend service for Ludo Binge. Built with Java 21, Spring Boot 4.x, PostgreSQL, Spring JDBC, Flyway, and Actuator.

---

## 🚀 Deploying on Render

This repository is pre-configured for one-click deployment on [Render](https://render.com) using the included `render.yaml` blueprint and multi-stage `Dockerfile`.

### Option A: Render Blueprint (Recommended)
1. Go to the [Render Dashboard](https://dashboard.render.com).
2. Click **New +** > **Blueprint**.
3. Connect your repository: `https://github.com/shivamShankhdhar/LUDO-BINGE-BACKEND.git`.
4. Render will automatically detect `render.yaml` and provision:
   - **Web Service** (`ludo-binge-backend`): Docker runtime running the Spring Boot application on port `8080`.
   - **PostgreSQL Database** (`ludo-binge-db`): Dedicated PostgreSQL database with automatic connection string wiring.
5. Click **Apply**.

### Option B: Deploy as a Render Web Service (Existing Database)
1. In Render, select **New +** > **Web Service**.
2. Connect your repository.
3. Runtime: Select **Docker**.
4. Health Check Path: `/actuator/health`
5. Under **Environment Variables**, provide your database connection:
   - `DATABASE_URL`: `postgres://user:password@host:port/database` (Render's internal URL or Neon/Supabase)
   - *Note*: `docker-entrypoint.sh` automatically converts standard `postgres://` URLs into the required `jdbc:postgresql://` format for Spring Boot.

---

## ⚙️ Environment Variables

| Variable | Description | Default / Example |
|---|---|---|
| `PORT` | Web server port (set by Render automatically) | `8080` |
| `DATABASE_URL` | PostgreSQL connection string | `postgres://user:pass@host:5432/dbname` |
| `SPRING_DATASOURCE_URL` | Explicit Spring JDBC URL (optional override) | `jdbc:postgresql://host:5432/ludo_binge` |
| `SPRING_DATASOURCE_USERNAME` | Database username | `ludo_binge` |
| `SPRING_DATASOURCE_PASSWORD` | Database password | - |
| `DB_POOL_SIZE` | HikariCP maximum connections | `10` |
| `GOOGLE_CLIENT_ID` | Google OAuth Client ID for identity verification | Optional |
| `LIVEKIT_URL` / `API_KEY` | LiveKit media configuration for voice chat | Optional |

---

## 💻 Local Development

### Requirements
- JDK 21+
- PostgreSQL 16+ or Docker

### Run locally with Maven
```sh
# Run tests
./mvnw test

# Run application
./mvnw spring-boot:run
```

### Run with Local Docker Compose
```sh
docker compose up -d --wait
export SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5432/ludo_binge'
export SPRING_DATASOURCE_USERNAME=ludo_binge
export SPRING_DATASOURCE_PASSWORD=local-development-only
./mvnw spring-boot:run
```

---

## 🔍 Health Checks & API Endpoints

- **Health check**: `GET /actuator/health`
- **Readiness check**: `GET /actuator/health/readiness`
- **Liveness check**: `GET /actuator/health/liveness`
- **Capabilities & config**: `GET /api/v1/capabilities`
- **Auth guest token**: `POST /api/v1/auth/guest`
- **Auth Google**: `POST /api/v1/auth/google`
- **WebSocket Gateway**: `/ws`

---

## 🛠️ Tech Stack & Architecture

- **Runtime**: Java 21 LTS
- **Framework**: Spring Boot 4.1.1 (WebMVC, Security, WebSocket, Actuator)
- **Database**: PostgreSQL with Spring JDBC / JdbcClient (parameterized queries)
- **Migrations**: Flyway migration management (`src/main/resources/db/migration`)
- **Connection Pool**: HikariCP with custom timeout bounds
- **Containerization**: Multi-stage Docker image using `eclipse-temurin:21`
