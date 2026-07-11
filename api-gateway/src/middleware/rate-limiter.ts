import { Request, Response, NextFunction } from 'express';
import Redis from 'ioredis';

// Connect to local Redis cluster setup
const redis = new Redis({
  host: process.env.REDIS_HOST || 'localhost',
  port: parseInt(process.env.REDIS_PORT || '6379'),
});

// Lua script to implement Token Bucket rate limiting
const RATE_LIMIT_LUA = `
  local key = KEYS[1]
  local capacity = tonumber(ARGV[1])
  local refill_rate = tonumber(ARGV[2])
  local now = tonumber(ARGV[3])
  local requested = tonumber(ARGV[4] or "1")

  local state = redis.call('HMGET', key, 'tokens', 'last_update')
  local tokens = tonumber(state[1])
  local last_update = tonumber(state[2])

  if not tokens then
    tokens = capacity
    last_update = now
  else
    local elapsed = math.max(0, now - last_update)
    tokens = math.min(capacity, tokens + elapsed * refill_rate)
  end

  if tokens >= requested then
    tokens = tokens - requested
    redis.call('HMSET', key, 'tokens', tokens, 'last_update', now)
    redis.call('EXPIRE', key, 86400) -- expire key after 1 day of inactivity
    return {1, tokens} -- allowed
  else
    return {0, tokens} -- rejected
  end
`;

export async function rateLimiter(req: Request, res: Response, next: NextFunction) {
  const merchantId = (req as any).merchantId || req.ip || 'anonymous';
  const key = `rate_limit:${merchantId}`;
  
  // Rate Limit Rules: Capacity = 100 requests, Refill rate = 10 tokens per second (10 reqs/sec)
  const capacity = 100;
  const refillRate = 10; 
  const now = Date.now() / 1000; // in seconds

  try {
    const result = await redis.eval(
      RATE_LIMIT_LUA,
      1,
      key,
      capacity,
      refillRate,
      now,
      1
    ) as [number, number];

    const [allowed, remainingTokens] = result;
    res.setHeader('X-RateLimit-Limit', capacity);
    res.setHeader('X-RateLimit-Remaining', Math.floor(remainingTokens));

    if (allowed === 1) {
      next();
    } else {
      res.status(429).json({
        error: 'Too Many Requests',
        message: 'API rate limit exceeded. Please back off and try again later.',
      });
    }
  } catch (error) {
    console.error('Rate limit error:', error);
    // Fail-open in case Redis is down to prioritize payment throughput, or fail-closed for security.
    // Financial platforms usually fail-open or alert dashboard operations. We will fail-open.
    next();
  }
}
