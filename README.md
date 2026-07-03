# VeriRun

A web-based Verilog simulation platform that uses Verilator to allow users to run simulations in a containerized environment.

## Prerequisites

- Java 21 & Maven
- Docker
-  PostgreSQL
-  Redis
-  S3-compatible storage

### 1. Start the backend

Configure your database, Redis, and AWS credentials in `src/main/resources/application.properties`, then run:

```bash
mvnw spring-boot:run
```

### 1. Start the frontend

```bash
cd frontend
npm install
npm run dev
```