# VeriRun

A web-based Verilog simulation platform that uses Verilator to run simulations in a containerized environment.

## Prerequisites

- Java 21
- Docker
- PostgreSQL
- Redis
- S3-compatible storage

## Backend

Configure your database, Redis, and AWS credentials in `src/main/resources/application.properties`, then run:

```bash
./mvnw spring-boot:run
```

## Frontend

```bash
cd frontend
npm install
npm run dev
```
