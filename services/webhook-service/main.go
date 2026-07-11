package main

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"fmt"
	"log"
	"net/http"
	"net/rpc"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	_ "github.com/lib/pq"
	"github.com/segmentio/kafka-go"
)

type WebhookTask struct {
	ID         string
	MerchantID string
	EventType  string
	Payload    string
	URL        string
	Secret     string
	Attempts   int
}

type WebhookWorkerPool struct {
	taskChan   chan WebhookTask
	db         *sql.DB
	httpClient *http.Client
	wg         sync.WaitGroup
}

func NewWorkerPool(workerCount int, db *sql.DB) *WebhookWorkerPool {
	return &WebhookWorkerPool{
		taskChan: make(chan WebhookTask, 1000),
		db:       db,
		httpClient: &http.Client{
			Timeout: 10 * time.Second,
		},
	}
}

func (wp *WebhookWorkerPool) Start(ctx context.Context, workerCount int) {
	for i := 0; i < workerCount; i++ {
		wp.wg.Add(1)
		go wp.worker(ctx, i)
	}
}

func (wp *WebhookWorkerPool) worker(ctx context.Context, id int) {
	defer wp.wg.Done()
	log.Printf("Webhook Worker %d started", id)

	for {
		select {
		case <-ctx.Done():
			log.Printf("Webhook Worker %d stopping...", id)
			return
		case task, ok := <-wp.taskChan:
			if !ok {
				return
			}
			wp.processWebhook(ctx, task)
		}
	}
}

func (wp *WebhookWorkerPool) processWebhook(ctx context.Context, task WebhookTask) {
	log.Printf("Worker executing webhook %s for merchant %s to %s", task.ID, task.MerchantID, task.URL)

	// 1. Generate HMAC-SHA256 signature
	signature := computeHMAC(task.Payload, task.Secret)

	// 2. Build HTTP request
	req, err := http.NewRequestWithContext(ctx, "POST", task.URL, bytes.NewBufferString(task.Payload))
	if err != nil {
		log.Printf("Error creating HTTP request: %v", err)
		return
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("PayCore-Signature", fmt.Sprintf("t=%d,v1=%s", time.Now().Unix(), signature))
	req.Header.Set("User-Agent", "PayCore-Webhook-Dispatcher/1.0")

	// 3. Fire request
	start := time.Now()
	resp, err := wp.httpClient.Do(req)
	duration := time.Since(start)

	var statusCode int
	var respBody string
	var status string

	if err != nil {
		log.Printf("HTTP transport failure on webhook %s: %v", task.ID, err)
		statusCode = 0
		respBody = err.Error()
		status = "RETRYING"
	} else {
		defer resp.Body.Close()
		statusCode = resp.StatusCode
		status = "SUCCESS"
		if statusCode < 200 || statusCode >= 300 {
			status = "RETRYING"
		}
	}

	task.Attempts++

	// 4. Handle retries or failure states
	if status == "RETRYING" {
		if task.Attempts >= 5 {
			status = "FAILED"
		} else {
			// Schedule retry with exponential backoff (15s, 30s, 60s, 120s...)
			backoffSeconds := (1 << task.Attempts) * 15
			nextRetry := time.Now().Add(time.Duration(backoffSeconds) * time.Second)
			log.Printf("Retrying webhook %s in %ds (Attempt %d/5)", task.ID, backoffSeconds, task.Attempts)

			_, err = wp.db.ExecContext(ctx,
				"UPDATE webhook_deliveries SET status=$1, attempts=$2, last_response_code=$3, last_response_body=$4, next_retry_at=$5, updated_at=NOW() WHERE id=$6",
				"RETRYING", task.Attempts, statusCode, truncateString(respBody, 500), nextRetry, task.ID,
			)
			if err != nil {
				log.Printf("Failed to update retry status in database: %v", err)
			}
			return
		}
	}

	// 5. Write final status log to Database
	_, err = wp.db.ExecContext(ctx,
		"UPDATE webhook_deliveries SET status=$1, attempts=$2, last_response_code=$3, last_response_body=$4, next_retry_at=NULL, updated_at=NOW() WHERE id=$5",
		status, task.Attempts, statusCode, truncateString(respBody, 500), task.ID,
	)
	if err != nil {
		log.Printf("Failed to commit webhook delivery outcome: %v", err)
	}

	log.Printf("Webhook %s completed with status: %s (Latency: %v)", task.ID, status, duration)
}

func computeHMAC(payload, secret string) string {
	h := hmac.New(sha256.New, []byte(secret))
	h.Write([]byte(payload))
	return hex.EncodeToString(h.Sum(nil))
}

func truncateString(s string, maxLen int) string {
	if len(s) > maxLen {
		return s[:maxLen]
	}
	return s
}

// Kafka Consumer to ingest events and feed them to the worker pool
func consumeKafkaEvents(ctx context.Context, pool *WebhookWorkerPool, db *sql.DB) {
	reader := kafka.NewReader(kafka.ReaderConfig{
		Brokers:  []string{"localhost:9092"},
		Topic:    "payment.authorized",
		GroupID:  "webhook-dispatcher-group",
		MinBytes: 10e3, // 10KB
		MaxBytes: 10e6, // 10MB
	})
	defer reader.Close()

	log.Println("Starting Webhook Kafka Consumer on topic payment.authorized...")

	for {
		msg, err := reader.ReadMessage(ctx)
		if err != nil {
			select {
			case <-ctx.Done():
				return
			default:
				log.Printf("Error reading message from Kafka: %v", err)
				time.Sleep(1 * time.Second)
				continue
			}
		}

		log.Printf("Received Kafka event on topic %s, key %s", msg.Topic, string(msg.Key))
		
		// In a real system, we parse the message, look up the merchant webhook configurations,
		// insert a new record in webhook_deliveries, and queue it in taskChan.
		// For the mock execution, we will create a mock task with sample merchant destination:
		task := WebhookTask{
			ID:         fmt.Sprintf("whd_%s", string(msg.Key)),
			MerchantID: "merchant_demo_123",
			EventType:  "payment.authorized",
			Payload:    string(msg.Value),
			URL:        "https://webhook.site/mock-endpoint", // Mock Merchant URL
			Secret:     "whsec_mock_secret_key_2026",
			Attempts:   0,
		}

		// Insert initial record in db
		_, dbErr := db.ExecContext(ctx,
			"INSERT INTO webhook_deliveries (id, merchant_id, event_type, payload, url, status, attempts, created_at, updated_at) VALUES ($1, $2, $3, $4, $5, $6, $7, NOW(), NOW())",
			task.ID, task.MerchantID, task.EventType, task.Payload, task.URL, "PENDING", 0,
		)
		if dbErr != nil {
			log.Printf("Database logging failed for webhook: %v", dbErr)
		}

		pool.taskChan <- task
	}
}

func main() {
	// Initialize Postgres Connection
	connStr := "postgresql://postgres:password@localhost:5432/paycore_webhook?sslmode=disable"
	db, err := sql.Open("postgres", connStr)
	if err != nil {
		log.Fatalf("Failed to connect to Webhook database: %v", err)
	}
	defer db.Close()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// Start worker pool (50 concurrency channels)
	pool := NewWorkerPool(50, db)
	pool.Start(ctx, 50)

	// Start Kafka consumer in background
	go consumeKafkaEvents(ctx, pool, db)

	// Simple RPC mock server to represent compiling gRPC
	// (Go-based services register RPC standard mapping)
	go func() {
		err := rpc.Register(pool)
		if err != nil {
			log.Printf("Failed to register RPC: %v", err)
		}
		rpc.HandleHTTP()
		log.Println("Go RPC server registered on port 50055")
	}()

	// Wait for shutdown signals
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
	<-sigChan

	log.Println("Shutting down Webhook service gracefully...")
	cancel()
	close(pool.taskChan)
	pool.wg.Wait()
	log.Println("Webhook service stopped.")
}
