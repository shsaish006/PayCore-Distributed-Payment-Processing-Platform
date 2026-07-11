import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

// k6 execution options
export const options = {
  scenarios: {
    // Scenario 1: Moderate load - 1,000 TPS
    tps_1000: {
      executor: 'constant-arrival-rate',
      rate: 1000,
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 100,
      maxVUs: 500,
    },
    // Scenario 2: High load ramp-up - 5,000 TPS
    /*
    tps_5000: {
      executor: 'constant-arrival-rate',
      rate: 5000,
      timeUnit: '1s',
      startTime: '35s',
      duration: '30s',
      preAllocatedVUs: 500,
      maxVUs: 2000,
    },
    */
    // Scenario 3: Peak capacity - 10,000 TPS
    /*
    tps_10000: {
      executor: 'constant-arrival-rate',
      rate: 10000,
      timeUnit: '1s',
      startTime: '70s',
      duration: '30s',
      preAllocatedVUs: 1000,
      maxVUs: 5000,
    },
    */
  },
  thresholds: {
    // Assert that P99 latency is below 100ms and HTTP failure rate is < 0.01%
    http_req_duration: ['p(99)<100'], 
    http_req_failed: ['rate<0.0001'],
  },
};

export default function () {
  const url = 'http://localhost:8000/v1/payments';
  
  const payload = JSON.stringify({
    merchant_id: 'merchant_demo_123',
    amount: (Math.random() * 500 + 5).toFixed(2), // random charge between $5 and $505
    currency: 'USD',
    payment_method: 'card',
    card_token: 'tok_visa_success',
    description: 'Load test payment simulation',
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'Authorization': 'Bearer sk_test_paycore_demo_key_2026',
      'Idempotency-Key': `idem_k6_${uuidv4()}`,
    },
  };

  const res = http.post(url, payload, params);

  check(res, {
    'status is 201': (r) => r.status === 201,
    'has payment id': (r) => r.json('id') !== undefined,
  });

  sleep(0.01); // 10ms pacing sleep
}
