package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/go-redis/redis/v8"
)

type AnalyzeRequest struct {
	TransactionID     string  `json:"transaction_id"`
	MerchantID        string  `json:"merchant_id"`
	Amount            float64 `json:"amount"`
	Currency          string  `json:"currency"`
	CardFingerprint   string  `json:"card_fingerprint"`
	DeviceFingerprint string  `json:"device_fingerprint"`
	IPAddress         string  `json:"ip_address"`
	BillingCountry    string  `json:"billing_country"`
}

type AnalyzeResponse struct {
	TransactionID  string   `json:"transaction_id"`
	IsFlagged      bool     `json:"is_flagged"`
	Score          float64  `json:"score"`
	Recommendation string   `json:"recommendation"`
	Reasons        []string `json:"reasons"`
}

type FraudEngine struct {
	rdb *redis.Client
}

func NewFraudEngine(redisAddr string) *FraudEngine {
	rdb := redis.NewClient(&redis.Options{
		Addr: redisAddr,
	})
	return &FraudEngine{rdb: rdb}
}

func (fe *FraudEngine) Analyze(ctx context.Context, req AnalyzeRequest) AnalyzeResponse {
	var reasons []string
	var score float64

	// Rule 1: Anomaly threshold (e.g. amount > 5000 USD is high risk)
	if req.Amount > 5000.0 {
		reasons = append(reasons, "AMOUNT_ANOMALY")
		score += 0.4
	}

	// Rule 2: Country Blacklist Check (e.g. high-risk corridors blocked automatically)
	highRiskCountries := map[string]bool{"KP": true, "IR": true, "SY": true}
	if highRiskCountries[req.BillingCountry] {
		reasons = append(reasons, "COUNTRY_BLACKLISTED")
		score += 0.9
	}

	// Rule 3: Velocity Check using Redis Sliding Window
	// Count card usage in last 1 minute
	cardKey := fmt.Sprintf("velocity:card:%s", req.CardFingerprint)
	now := time.Now().UnixNano()
	oneMinAgo := time.Now().Add(-1 * time.Minute).UnixNano()

	// Add current transaction time to sorted set
	_, err := fe.rdb.ZAdd(ctx, cardKey, &redis.Z{
		Score:  float64(now),
		Member: fmt.Sprintf("%d", now),
	}).Result()

	if err == nil {
		// Clean up items older than 1 minute
		fe.rdb.ZRemRangeByScore(ctx, cardKey, "-inf", fmt.Sprintf("%d", oneMinAgo))
		// Count card reuse in the sliding window
		cardCount, _ := fe.rdb.ZCard(ctx, cardKey).Result()
		fe.rdb.Expire(ctx, cardKey, 2*time.Minute)

		if cardCount > 10 {
			reasons = append(reasons, "CARD_VELOCITY_EXCEEDED")
			score += 0.5
		}
	}

	// Rule 4: Device Velocity Check
	deviceKey := fmt.Sprintf("velocity:device:%s", req.DeviceFingerprint)
	_, err = fe.rdb.ZAdd(ctx, deviceKey, &redis.Z{
		Score:  float64(now),
		Member: fmt.Sprintf("%d", now),
	}).Result()

	if err == nil {
		fe.rdb.ZRemRangeByScore(ctx, deviceKey, "-inf", fmt.Sprintf("%d", oneMinAgo))
		deviceCount, _ := fe.rdb.ZCard(ctx, deviceKey).Result()
		fe.rdb.Expire(ctx, deviceKey, 2*time.Minute)

		if deviceCount > 15 {
			reasons = append(reasons, "DEVICE_VELOCITY_EXCEEDED")
			score += 0.5
		}
	}

	// Normalize score
	if score > 1.0 {
		score = 1.0
	}

	recommendation := "APPROVE"
	isFlagged := false
	if score >= 0.7 {
		recommendation = "REJECT"
		isFlagged = true
	} else if score >= 0.3 {
		recommendation = "REVIEW"
		isFlagged = true
	}

	return AnalyzeResponse{
		TransactionID:  req.TransactionID,
		IsFlagged:      isFlagged,
		Score:          score,
		Recommendation: recommendation,
		Reasons:        reasons,
	}
}

func main() {
	redisAddr := os.Getenv("REDIS_HOST") + ":" + os.Getenv("REDIS_PORT")
	if redisAddr == ":" {
		redisAddr = "localhost:6379"
	}

	engine := NewFraudEngine(redisAddr)
	log.Printf("Fraud Engine running. Redis connection: %s", redisAddr)

	// Expose HTTP REST Endpoint
	http.HandleFunc("/v1/fraud/analyze", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}

		var req AnalyzeRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "Bad request", http.StatusBadRequest)
			return
		}

		resp := engine.Analyze(r.Context(), req)
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(resp)
	})

	// Run REST server on port 8084
	server := &http.Server{Addr: ":8084"}
	go func() {
		log.Println("REST server listing on port :8084")
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("Listen failed: %v", err)
		}
	}()

	// Simulating gRPC receiver on port 50054
	// (Go compiles gRPC socket server cleanly)
	go func() {
		listener, err := net.Listen("tcp", ":50054")
		if err != nil {
			log.Printf("gRPC mock listener failed: %v", err)
			return
		}
		defer listener.Close()
		log.Println("gRPC Fraud socket listening on port :50054")
		
		// In a real system, grpc.NewServer() is registered here.
		// Since we want standard out-of-box compile, we leave socket alive to satisfy gateway client dials.
		for {
			conn, err := listener.Accept()
			if err != nil {
				continue
			}
			go func(c net.Conn) {
				// Keeps TCP channel alive or processes mock frames
				buf := make([]byte, 1024)
				for {
					_, err := c.Read(buf)
					if err != nil {
						c.Close()
						break
					}
				}
			}(conn)
		}
	}()

	// Graceful Shutdown
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
	<-sigChan

	log.Println("Shutting down Fraud service...")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	server.Shutdown(ctx)
	log.Println("Fraud service stopped.")
}
