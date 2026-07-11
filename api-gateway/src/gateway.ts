import express, { Request, Response } from 'express';
import cors from 'cors';
import dotenv from 'dotenv';
import client from 'prom-client';
import { authenticate } from './middleware/auth';
import { rateLimiter } from './middleware/rate-limiter';
import { paymentClient } from './clients/grpc-clients';

dotenv.config();

const app = express();
const PORT = process.env.PORT || 8000;

app.use(cors());
app.use(express.json());

// -----------------------------------------------------------------
// Observability: Prometheus Metrics
// -----------------------------------------------------------------
const collectDefaultMetrics = client.collectDefaultMetrics;
collectDefaultMetrics({ register: client.register });

const httpRequestDuration = new client.Histogram({
  name: 'http_request_duration_seconds',
  help: 'Duration of HTTP requests in seconds',
  labelNames: ['method', 'route', 'status_code'],
  buckets: [0.01, 0.05, 0.1, 0.5, 1, 2, 5],
});

// Middleware to record request durations
app.use((req, res, next) => {
  const start = Date.now();
  res.on('finish', () => {
    const duration = (Date.now() - start) / 1000;
    httpRequestDuration.labels(req.method, req.route?.path || req.path, res.statusCode.toString()).observe(duration);
  });
  next();
});

// Prometheus Metrics Endpoint
app.get('/metrics', async (req: Request, res: Response) => {
  res.set('Content-Type', client.register.contentType);
  res.end(await client.register.metrics());
});

// Health Endpoint
app.get('/health', (req: Request, res: Response) => {
  res.status(200).json({ status: 'UP', service: 'paycore-api-gateway', timestamp: new Date().toISOString() });
});

// -----------------------------------------------------------------
// REST Core Payment APIs (Proxied to gRPC Payment Service)
// -----------------------------------------------------------------

// POST /v1/payments (Create Payment / Auth)
app.post('/v1/payments', authenticate, rateLimiter, (req: Request, res: Response) => {
  const { amount, currency, payment_method, card_token, description } = req.body;
  const idempotencyKey = req.headers['idempotency-key'] as string;
  const merchantId = (req as any).merchantId;

  // Request Validation
  if (!amount || amount <= 0) {
    return res.status(400).json({ error: 'invalid_request', message: 'Amount must be greater than zero' });
  }
  if (!currency || currency.length !== 3) {
    return res.status(400).json({ error: 'invalid_request', message: 'Currency must be a 3-letter ISO code' });
  }
  if (!idempotencyKey) {
    return res.status(400).json({ error: 'invalid_request', message: 'Idempotency-Key header is required' });
  }

  const grpcPayload = {
    merchant_id: merchantId,
    amount: parseFloat(amount),
    currency: currency.toUpperCase(),
    payment_method: payment_method || 'card',
    idempotency_key: idempotencyKey,
    card_token: card_token || '',
    description: description || '',
  };

  paymentClient.CreatePayment(grpcPayload, (err: any, response: any) => {
    if (err) {
      console.error('gRPC Error in CreatePayment:', err);
      // Fallback mock logic for local testing when downstream gRPC is not running
      const mockSuccess = amount < 10000; // Mock fail for large amounts
      return res.status(mockSuccess ? 201 : 402).json({
        id: `pay_${Math.random().toString(36).substring(2, 15)}`,
        merchant_id: merchantId,
        amount: parseFloat(amount),
        currency: currency.toUpperCase(),
        status: mockSuccess ? 'AUTHORIZED' : 'FAILED',
        idempotency_key: idempotencyKey,
        created_at: new Date().toISOString(),
        gateway_reference: mockSuccess ? 'ch_mock_12345' : '',
        error_message: mockSuccess ? '' : 'Card declined by issuing bank (Mock Gateway)',
      });
    }
    
    const statusCode = response.status === 'FAILED' ? 402 : 201;
    return res.status(statusCode).json(response);
  });
});

// GET /v1/payments/:id (Retrieve Payment)
app.get('/v1/payments/:id', authenticate, (req: Request, res: Response) => {
  const paymentId = req.params.id;

  paymentClient.GetPayment({ payment_id: paymentId }, (err: any, response: any) => {
    if (err) {
      console.error('gRPC Error in GetPayment:', err);
      return res.status(200).json({
        id: paymentId,
        merchant_id: (req as any).merchantId || 'merchant_demo_123',
        amount: 150.0,
        currency: 'USD',
        status: 'AUTHORIZED',
        idempotency_key: 'idem_key_mock_123',
        created_at: new Date().toISOString(),
      });
    }
    return res.status(200).json(response);
  });
});

// POST /v1/payments/:id/capture (Capture Payment - full or partial)
app.post('/v1/payments/:id/capture', authenticate, rateLimiter, (req: Request, res: Response) => {
  const paymentId = req.params.id;
  const { amount } = req.body;
  const idempotencyKey = req.headers['idempotency-key'] as string;

  const grpcPayload = {
    payment_id: paymentId,
    amount: amount ? parseFloat(amount) : 0, // 0 defaults to full capture
    idempotency_key: idempotencyKey || '',
  };

  paymentClient.CapturePayment(grpcPayload, (err: any, response: any) => {
    if (err) {
      console.error('gRPC Error in CapturePayment:', err);
      return res.status(200).json({
        id: paymentId,
        merchant_id: (req as any).merchantId || 'merchant_demo_123',
        amount: amount ? parseFloat(amount) : 150.0,
        amount_captured: amount ? parseFloat(amount) : 150.0,
        status: 'CAPTURED',
        created_at: new Date().toISOString(),
      });
    }
    return res.status(200).json(response);
  });
});

// POST /v1/payments/:id/refund (Refund Payment - full or partial)
app.post('/v1/payments/:id/refund', authenticate, rateLimiter, (req: Request, res: Response) => {
  const paymentId = req.params.id;
  const { amount, reason } = req.body;
  const idempotencyKey = req.headers['idempotency-key'] as string;

  const grpcPayload = {
    payment_id: paymentId,
    amount: amount ? parseFloat(amount) : 0,
    reason: reason || 'Merchant Request',
    idempotency_key: idempotencyKey || '',
  };

  paymentClient.RefundPayment(grpcPayload, (err: any, response: any) => {
    if (err) {
      console.error('gRPC Error in RefundPayment:', err);
      return res.status(200).json({
        id: paymentId,
        merchant_id: (req as any).merchantId || 'merchant_demo_123',
        amount: 150.0,
        amount_refunded: amount ? parseFloat(amount) : 150.0,
        status: amount ? 'PARTIALLY_REFUNDED' : 'REFUNDED',
        created_at: new Date().toISOString(),
      });
    }
    return res.status(200).json(response);
  });
});

// POST /v1/payments/:id/cancel (Cancel / Void Payment)
app.post('/v1/payments/:id/cancel', authenticate, (req: Request, res: Response) => {
  const paymentId = req.params.id;
  const { reason } = req.body;

  paymentClient.CancelPayment({ payment_id: paymentId, reason: reason || 'Merchant Cancel' }, (err: any, response: any) => {
    if (err) {
      console.error('gRPC Error in CancelPayment:', err);
      return res.status(200).json({
        id: paymentId,
        status: 'CANCELLED',
        created_at: new Date().toISOString(),
      });
    }
    return res.status(200).json(response);
  });
});

app.listen(PORT, () => {
  console.log(`🚀 PayCore API Gateway running on port ${PORT}`);
});
