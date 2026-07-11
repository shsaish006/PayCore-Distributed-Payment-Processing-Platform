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

## Distributed Systems Theory & Architecture Deep-Dive

PayCore is designed to solve standard challenges in highly concurrent, distributed financial systems: consistency, idempotency, throughput, and fault isolation.

### 1. The Distributed Transaction Problem & Saga Orchestration
Traditional relational databases rely on Two-Phase Commit (2PC) or XA transactions to guarantee ACID properties across database nodes. However, in a microservice architecture, 2PC creates a massive coordinator bottleneck, blocks resources, and introduces significant latency (violating the P99 < 100ms requirement).

PayCore utilizes an **Orchestrator-based Saga Pattern** to ensure **Eventual Consistency**:
* **Happy Path**: The `payment-service` orchestrates calls to the Fraud Service, Card Gateway, and Ledger Service sequentially. If all succeed, the transaction is marked `AUTHORIZED` and eventually `CAPTURED`.
* **Compensation Path (Rollback)**: If the payment is authorized at the card gateway, but the Ledger Service is down or rejects the write, the orchestrator executes a compensation. It invokes a `CancelPayment` command to void the authorization on the bank network, preventing the "orphan charge" problem where a customer is debited but the platform has no record of the ledger deposit.

### 2. Resolving the Dual-Write Dilemma (Transactional Outbox)
A common failure state in event-driven microservices is the "dual-write" problem: updating a database and publishing an event to Kafka. If the database update succeeds but the network fails before publishing the event, the system drifts out of sync. If the event is published first but the database commit fails, downstream services process ghost transactions.

PayCore resolves this using the **Transactional Outbox Pattern**:
* The payment update and the outbox event payload are committed inside the **same local database transaction**.
* A background `OutboxPublisher` polls the `outbox_events` table periodically, dispatches events to Kafka, and marks them `PROCESSED` upon broker acknowledgement.
* This guarantees **At-Least-Once Delivery** to Kafka.

### 3. Exactly-Once Processing (Inbox Pattern & Idempotency)
Because the Transactional Outbox guarantees At-Least-Once delivery, message consumers may receive duplicate events due to network retries.
To enforce **Exactly-Once Processing**:
* **Ingest Rate Limiting / Locking**: API Gateway uses atomic Lua scripts in Redis to enforce Token-Bucket rules. The Saga orchestrator locks the `idempotency_key` in Redis, preventing concurrent duplicate requests from executing simultaneously.
* **Inbox Pattern**: The `ledger-service` maintains an `inbox_messages` table. When an event is consumed from Kafka, the consumer attempts to insert the event signature. A unique constraint violation causes duplicate messages to be discarded silently, ensuring ledger records are never duplicated.

### 4. High-Throughput Concurrency (Java 21 Virtual Threads vs. Go Goroutines)
PayCore employs two distinct concurrency runtime architectures:
* **Java 21 Virtual Threads (Loom)**: Traditional Java threads map 1-to-1 to kernel OS threads, consuming ~1MB of memory and requiring costly kernel context switches. PayCore’s Spring Boot 3 runtime uses Virtual Threads, which map $N$ virtual threads to $M$ OS carrier threads (using a ForkJoinPool scheduler). When a thread executes a blocking database IO operation, it yields its carrier thread, reducing context-switch CPU cycles to virtually zero and dropping memory overhead to &lt;2KB per thread.
* **Go Goroutines**: Go uses a work-stealing scheduler (`G-M-P` model) to run goroutines on logical processors. Webhook workers and fraud checks execute on these lightweight green threads. Go dynamically grows goroutine stacks (starting at 2KB), allowing PayCore to orchestrate thousands of concurrent HTTP webhook dispatch checks on a single Go node.

### 5. Concurrency Controls & Lock-Free Data Structures
To maximize throughput, the platform avoids synchronized blocks that create kernel locks:
* **Compare-And-Swap (CAS)**: Used in counters and balance checks to update registers. CAS compares the current memory content with a given value, and only updates it if they match. This prevents CPU-level context switching.
* **ConcurrentHashMap**: Java cache stores utilize segment-based lock striping, allowing multiple threads to write to separate hash buckets concurrently without blocking the entire data structure.
* **Atomic Operations in Go**: The Go Analytics service tracks counters and revenue metrics using `sync/atomic`, executing lock-free additions directly via CPU assembly instructions.

### 6. Pessimistic vs. Optimistic Locking in Ledgers
To balance performance against financial ledger accuracy, PayCore segregates locking strategies:
* **Optimistic Locking**: The `accounts` table uses version-based optimistic locking (`@Version`). On update, it executes `WHERE version = :currentVersion`. If another request modified the balance in the interim, the transaction fails with an optimistic locking error, triggering a clean retry. This is optimal for merchant accounts with moderate transaction frequencies.
* **Pessimistic Locking**: For ledger settlements and payouts, PayCore acquires exclusive row-level locks (`SELECT ... FOR UPDATE`). This blocks other transactions from modifying the record until the settlement completes, preventing **Double-Spending** attacks.

### 7. Kafka Message Ordering & Partitioning
Kafka ensures high throughput by distributing messages across multiple partitions. However, random distribution destroys transaction order (e.g., processing a `Captured` event before an `Initiated` event).
* **Key-Based Partitioning**: PayCore publishes events using the `payment_id` as the message key. Kafka hashes the key and maps it to a specific partition:
  $$\text{Partition} = \text{Hash}(\text{Key}) \pmod{\text{Number of Partitions}}$$
* This guarantees that all lifecycle events for a specific transaction are routed to the **same partition** and consumed in strict **FIFO order** by consumer groups.

### 8. PCI-DSS inspired Tokenization & API Key Security
Financial software must adhere to strict PCI-DSS security compliance limits:
* **Tokenization Scope**: The API Gateway and core payment services never handle or store raw cardholder data (PAN, CVV). Raw details are exchanged directly for single-use string tokens (e.g., `tok_visa_success`) at the Authorization Service boundary. This restricts the cardholder data environment (CDE) to a single service, reducing audit scope.
* **Hashed API Keys**: PayCore generates cryptographically secure API keys (`sk_live_...`). To prevent database leaks from exposing keys, we store only the SHA-256 hash of the keys. When a merchant authenticates, we hash the provided key and query PostgreSQL for a match.

### 9. Cascading Failures & Resilience (Circuit Breakers & Bulkheads)
In a highly distributed environment, one slow microservice can consume resources across the cluster, causing a cascading failure:
* **Circuit Breakers**: When gRPC calls to the Ledger or Authorization Service fail repeatedly, the circuit breaker trips from `CLOSED` to `OPEN`. Subsequent calls fail fast immediately, shielding the failing service and preserving thread resources in the calling service.
* **Bulkhead Isolation**: Isolates thread resources. Webhook worker pools and payment Saga orchestrators run in dedicated thread groups. If webhook delivery targets are slow and consume all webhook pool resources, payment processing saga threads remain unimpacted.

---


## Verification & Test Suite Use Cases

Rather than simple verification, our test suite targets validation of distributed systems failure modes:

### 1. Mock Saga Lifecycles (JUnit 5 + Mockito)
Located at `com.paycore.payment.domain.saga.PaymentSagaOrchestratorTest`, the JUnit test suite verifies state transition rules:
* **Gateway Decline Simulation**: Validates that if the Card Authorization returns a decline, the Saga halts immediately, updates the status to `FAILED`, and never triggers the Ledger.
* **Ledger Write Failure (Compensation trigger)**: Validates that if gRPC calls to the Ledger Service fail, the Saga catches the error, calls the compensating `CancelPayment` RPC at the gateway service, and registers the transaction as `FAILED`. This tests the safety limits of our financial balance sheet.

### 2. High-Load TPS Benchmarks (k6 Scripting)
Located in `tests/k6/load_test.js`, this script runs Constant Arrival-Rate tests to analyze the platform at load scales:
* **1,000 TPS**: Baseline validation. Monitors CPU and memory footprints of the API Gateway under normal operating conditions.
* **5,000 TPS**: Stress testing Redis Lua script execution rates and connection pool recovery inside PostgreSQL Hikari CP.
* **10,000 TPS**: Evaluates virtual thread scheduling queues, gRPC connection multiplexing limits, and Kafka partition lags.

### 3. Distributed Tracing & Observability Verification
Using the **Jaeger** and **Prometheus** integrations:
* **Trace Propagation**: Verifies that the OTLP (OpenTelemetry) context `traceparent` headers propagate correctly from the client request at the API Gateway, through internal Java gRPC calls, Kafka events, and into Go background webhook deliveries.
* **System Latency Budgets**: Monitors average REST API latencies against target SLA metrics (&lt;40ms average, &lt;100ms P99) to pinpoint bottlenecks inside database connection pools or network latency overheads.

