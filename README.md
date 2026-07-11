# PayCore - Enterprise Distributed Payment Processing Platform

PayCore is a highly scalable, fault-tolerant, and low-latency payment processing platform modeled after the backend architectures of Stripe, Adyen, and Razorpay. It is designed to sustain 10,000+ TPS with sub-40ms average API latency and P99 latency under 100ms.

## Architecture Highlights

PayCore is structured as a monorepo containing multiple specialized microservices communicating via **gRPC** for low-latency internal RPCs and **Apache Kafka** for reliable asynchronous event propagation.

```
                               ┌────────────────────────┐
                               │  Next.js Merchant UI   │
                               └───────────┬────────────┘
                                           │ (HTTP REST)
                               ┌───────────▼────────────┐
                               │   API Gateway (TS)     │
                               └───────────┬────────────┘
                                           │ (gRPC Internal)
                  ┌────────────────────────┼────────────────────────┐
                  │                        │                        │
       ┌──────────▼──────────┐  ┌──────────▼──────────┐  ┌──────────▼──────────┐
       │   Auth Service      │  │   Payment Service   │  │   Fraud Service     │
       │  (Java/Spring Boot) │  │(Java 21/Spring Boot)│  │      (Go)           │
       └─────────────────────┘  └──────────┬──────────┘  └─────────────────────┘
                                           │ (gRPC)
                                ┌──────────▼──────────┐
                                │   Ledger Service    │
                                │ (Java/Spring Boot)  │
                                └──────────┬──────────┘
                                           │ (Transactional Outbox)
                                ┌──────────▼──────────┐
                                │    Apache Kafka     │
                                └──────────┬──────────┘
                                           │
                                ┌──────────▼──────────┐
                                │   Webhook Service   │
                                │      (Go)           │
                                └─────────────────────┘
```

### Advanced Distributed Systems Patterns
* **Saga Pattern (Distributed Transactions)**: Coordinates payment creation across Fraud Analysis, Card Authorization, and Ledger Bookkeeping. Initiates a compensation (void/reversal) workflow at the gateway level if database or ledger commits fail.
* **Transactional Outbox Pattern**: Assures database updates (payment states) and event publishes (Kafka messages) are committed atomically in PostgreSQL before Kafka delivery.
* **Inbox Pattern (Idempotency)**: Consuming services (like Ledger and Webhook) write message signatures to `inbox_messages` before processing to guarantee **Exactly-Once Delivery**.
* **Redis Distributed Locks**: Locks idempotency keys on incoming gateway requests to block race conditions.
* **Java 21 Virtual Threads**: Standardized JVM task executors to handle high concurrency with light-weight thread utilization.

---

## Service Registry (Ports & Interfaces)

| Service Name | Language / Core Tech | REST Port | gRPC Port | Database Name |
| :--- | :--- | :--- | :--- | :--- |
| **API Gateway** | TypeScript / Express | `8000` | — | — |
| **Auth Service** | Java 21 / Spring Boot 3 | `8086` | `50056` | `paycore_auth` |
| **Merchant Service** | Java 21 / Spring Boot 3 | `8087` | — | `paycore_merchant` |
| **Payment Service** | Java 21 / Spring Boot 3 | `8081` | `50051` | `paycore_payment` |
| **Authorization Service** | Java 21 / Spring Boot 3 | `8082` | `50052` | `paycore_authorization`|
| **Capture Service** | Java 21 / Spring Boot 3 | `8088` | — | `paycore_capture` |
| **Refund Service** | Java 21 / Spring Boot 3 | `8089` | — | `paycore_refund` |
| **Webhook Service** | Go | — | `50055` | `paycore_webhook` |
| **Ledger Service** | Java 21 / Spring Boot 3 | `8083` | `50053` | `paycore_ledger` |
| **Settlement Service** | Java 21 / Spring Boot 3 | `8090` | — | `paycore_settlement` |
| **Fraud Detection Service**| Go | `8084` | `50054` | — |
| **Notification Service** | Java 21 / Spring Boot 3 | `8091` | — | `paycore_notification` |
| **Analytics Service** | Go | `8093` | — | — |
| **Audit Service** | Java 21 / Spring Boot 3 | `8092` | — | `paycore_audit` |

---

## Local Development Setup

### Prereqs
* Docker and Docker Compose
* Java 21 JDK + Maven 3.8+
* Go 1.21+
* Node.js 18+ (npm)

### Step 1: Start the Infrastructure Stack
Run the following command to spin up PostgreSQL, Redis, Kafka, Prometheus, Grafana, Jaeger, and Loki:
```bash
cd infrastructure/docker
docker-compose up -d
```

### Step 2: Compile & Build Java Microservices
From the project root directory, run Maven to build the shared protobuf module and all Spring Boot services:
```bash
mvn clean install -DskipTests
```

### Step 3: Run Spring Boot Services
Start the key Spring Boot services (in separate terminal windows or as background jobs):
```bash
# Start Payment Service
cd services/payment-service
mvn spring-boot:run

# Start Ledger Service
cd ../ledger-service
mvn spring-boot:run

# Start Authorization Service
cd ../authorization-service
mvn spring-boot:run

# Start Auth Service
cd ../auth-service
mvn spring-boot:run
```

### Step 4: Run Go Services
Start the Go worker nodes:
```bash
# Start Fraud Service
cd services/fraud-detection-service
go run main.go

# Start Webhook Service
cd ../webhook-service
go run main.go

# Start Analytics Service
cd ../analytics-service
go run main.go
```

### Step 5: Start TypeScript API Gateway
Install dependencies and launch the Express router:
```bash
cd api-gateway
npm install
npm run dev
```

### Step 6: Start the Next.js Merchant Portal
Start the Next.js developer dashboard:
```bash
cd frontend
npm install
npm run dev
```
Open [http://localhost:3001](http://localhost:3001) in your browser to access the dashboard.

---

## Load Testing & Benchmarking

We use `k6` to run concurrent performance benchmarks.
To execute a load test simulating 1000 requests per second against your local sandbox:
```bash
# Install k6 locally first (e.g. winget install gnu.k6 or brew install k6)
k6 run tests/k6/load_test.js
```

---

## Observability Dashboards

* **Distributed Tracing (Jaeger)**: Access at [http://localhost:16686](http://localhost:16686) to trace transaction spans across API Gateway, Payment Saga, Ledger, and Kafka.
* **Metrics & Analytics (Grafana)**: Access at [http://localhost:3000](http://localhost:3000) (Credentials: `admin`/`admin`). A pre-configured **PayCore Observability** dashboard displays active TPS, latencies, success rates, and Kafka consumer lags.
