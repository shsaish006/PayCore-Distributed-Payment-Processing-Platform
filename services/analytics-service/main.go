package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/segmentio/kafka-go"
)

type PaymentEvent struct {
	ID         string  `json:"id"`
	MerchantID string  `json:"merchant_id"`
	Amount     float64 `json:"amount"`
	Currency   string  `json:"currency"`
	Status     string  `json:"status"`
}

type MetricsTracker struct {
	successCount uint64
	failedCount  uint64
	totalRevenue uint64 // Stored in cents to use atomic operations safely
}

var tracker MetricsTracker

func consumeKafkaEvents(ctx context.Context) {
	reader := kafka.NewReader(kafka.ReaderConfig{
		Brokers:  []string{"localhost:9092"},
		Topic:    "payment.authorized", // Scrapes successful authorizations
		GroupID:  "analytics-group",
		MinBytes: 10e3,
		MaxBytes: 10e6,
	})
	defer reader.Close()

	log.Println("Starting Analytics Kafka Consumer...")

	for {
		msg, err := reader.ReadMessage(ctx)
		if err != nil {
			select {
			case <-ctx.Done():
				return
			default:
				log.Printf("Error reading analytics event: %v", err)
				time.Sleep(1 * time.Second)
				continue
			}
		}

		var event PaymentEvent
		if err := json.Unmarshal(msg.Value, &event); err != nil {
			log.Printf("Failed to unmarshal payment event: %v", err)
			continue
		}

		if event.Status == "AUTHORIZED" || event.Status == "CAPTURED" {
			atomic.AddUint64(&tracker.successCount, 1)
			revenueCents := uint64(event.Amount * 100)
			atomic.AddUint64(&tracker.totalRevenue, revenueCents)
			log.Printf("Analytics: Tracked successful payment %s, Amount: %f", event.ID, event.Amount)
		} else {
			atomic.AddUint64(&tracker.failedCount, 1)
			log.Printf("Analytics: Tracked failed payment %s", event.ID)
		}
	}
}

func main() {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// Consume Kafka stream in background
	go consumeKafkaEvents(ctx)

	// Expose Prometheus metrics endpoint on port 8093 (or fallback scrapers)
	http.HandleFunc("/metrics", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain; version=0.0.4")
		
		success := atomic.LoadUint64(&tracker.successCount)
		failed := atomic.LoadUint64(&tracker.failedCount)
		revenue := float64(atomic.LoadUint64(&tracker.totalRevenue)) / 100.0

		fmt.Fprintf(w, "# HELP paycore_payments_processed_total Total number of payments processed.\n")
		fmt.Fprintf(w, "# TYPE paycore_payments_processed_total counter\n")
		fmt.Fprintf(w, "paycore_payments_processed_total{status=\"success\"} %d\n", success)
		fmt.Fprintf(w, "paycore_payments_processed_total{status=\"failed\"} %d\n", failed)

		fmt.Fprintf(w, "# HELP paycore_revenue_usd_total Cumulative processed revenue in USD.\n")
		fmt.Fprintf(w, "# TYPE paycore_revenue_usd_total counter\n")
		fmt.Fprintf(w, "paycore_revenue_usd_total %f\n", revenue)
	})

	server := &http.Server{Addr: ":8093"}
	go func() {
		log.Println("Analytics metrics server running on port :8093")
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("Listen failed: %v", err)
		}
	}()

	// Await signal
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
	<-sigChan

	log.Println("Shutting down Analytics service...")
	cancel()
	server.Shutdown(ctx)
	log.Println("Analytics service stopped.")
}
