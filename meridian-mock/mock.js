'use strict';

/**
 * Meridian Pay mock: implements the supplied Meridian spec (docs/meridian-openapi.yaml)
 * so the whole stack runs offline. Development and tests only, never production.
 *
 * Zero dependencies: Node's http, crypto and fetch.
 * Never logs card numbers, tokens, signatures or webhook bodies.
 */
const http = require('node:http');
const crypto = require('node:crypto');

const REASON_MESSAGES = {
  AM04: 'Insufficient funds',
  AC04: 'Card closed or blocked',
  FF01: 'Invalid card data',
  NARR: 'Other, see provider logs',
};

/**
 * Magic test tokens. Tokenisation happens before our API and is out of scope, so the mock has
 * no /tokens endpoint: it accepts any single-use token shaped like tok_<letters, digits, _ or ->
 * and picks the outcome from the token's prefix. Add a unique suffix to each token you use,
 * for example tok_decline_funds_1.
 */
const MAGIC_TOKENS = [
  { prefix: 'tok_decline_funds', outcome: 'RJCT', reason: 'AM04' },
  { prefix: 'tok_decline_blocked', outcome: 'RJCT', reason: 'AC04' },
  { prefix: 'tok_decline_invalid', outcome: 'RJCT', reason: 'FF01' },
  { prefix: 'tok_decline_other', outcome: 'RJCT', reason: 'NARR' },
  { prefix: 'tok_slow', outcome: 'ACCP', slow: true },
];
const TOKEN_PATTERN = /^tok_[A-Za-z0-9_-]{1,60}$/;
/** Meridian echoes a masked card in payment responses; the mock never sees a card, so it is fixed. */
const MASKED_PAN = '411111******1111';

function outcomeForToken(token) {
  return MAGIC_TOKENS.find((magic) => token.startsWith(magic.prefix)) ?? { outcome: 'ACCP' };
}

const DEFAULTS = {
  port: 9090,
  apiKey: 'mock-api-key',
  webhookSecret: 'local-dev-webhook-secret',
  allowedOrigin: '*',
  authDelayMs: 10000,
  slowCallbackMs: 3 * 60 * 1000,
  settleAfterMs: 30 * 1000,
  retryMs: 5000,
  maxDeliveryAttempts: 10,
  duplicateWebhooks: false,
  outOfOrder: false,
  outOfOrderMaxMs: 2000,
  log: (line) => console.log(line),
};

function configFromEnv(env) {
  const num = (value, fallback) => (value === undefined || value === '' ? fallback : Number(value));
  const bool = (value) => String(value).toLowerCase() === 'true';
  return {
    port: num(env.PORT, DEFAULTS.port),
    apiKey: env.MERIDIAN_API_KEY ?? DEFAULTS.apiKey,
    webhookSecret: env.MERIDIAN_WEBHOOK_SECRET ?? DEFAULTS.webhookSecret,
    allowedOrigin: env.MOCK_ALLOWED_ORIGIN ?? DEFAULTS.allowedOrigin,
    authDelayMs: num(env.MOCK_AUTH_DELAY_MS, DEFAULTS.authDelayMs),
    settleAfterMs: num(env.MOCK_SETTLE_AFTER_SECONDS, DEFAULTS.settleAfterMs / 1000) * 1000,
    duplicateWebhooks: bool(env.MOCK_DUPLICATE_WEBHOOKS),
    outOfOrder: bool(env.MOCK_OUT_OF_ORDER),
  };
}

/** Signature header value: lowercase hex HMAC-SHA256 of the raw body. */
function signBody(secret, rawBody) {
  return crypto.createHmac('sha256', secret).update(rawBody).digest('hex');
}

function sha256(text) {
  return crypto.createHash('sha256').update(text).digest('hex');
}

function safeEqual(a, b) {
  const left = Buffer.from(String(a));
  const right = Buffer.from(String(b));
  return left.length === right.length && crypto.timingSafeEqual(left, right);
}

function isPlainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function createMock(overrides = {}) {
  const config = { ...DEFAULTS, ...overrides };
  const usedTokens = new Set();
  const payments = new Map();
  const paymentKeys = new Map();
  const cancelKeys = new Map();
  const timers = new Set();
  let closed = false;

  function later(fn, ms) {
    if (closed) {
      return;
    }
    const timer = setTimeout(() => {
      timers.delete(timer);
      fn();
    }, ms);
    timer.unref();
    timers.add(timer);
  }

  // ---- webhooks -------------------------------------------------------

  async function deliver(payment, event, attempt) {
    const rawBody = JSON.stringify(event);
    let delivered = false;
    try {
      const response = await fetch(payment.callbackUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Meridian-Signature': signBody(config.webhookSecret, rawBody) },
        body: rawBody,
        signal: AbortSignal.timeout(5000),
      });
      delivered = response.ok;
    } catch (error) {
      delivered = false;
    }
    if (!delivered && attempt < config.maxDeliveryAttempts) {
      later(() => deliver(payment, event, attempt + 1), config.retryMs);
    }
  }

  function emit(payment) {
    const event = {
      eventId: 'evt_' + crypto.randomUUID().replace(/-/g, '').slice(0, 12),
      paymentId: payment.paymentId,
      endToEndId: payment.endToEndId,
      status: payment.status,
      statusReason: payment.statusReason,
      occurredAt: payment.updatedAt,
    };
    const copies = config.duplicateWebhooks ? 2 : 1;
    for (let i = 0; i < copies; i += 1) {
      const jitter = config.outOfOrder ? Math.floor(Math.random() * config.outOfOrderMaxMs) : 0;
      later(() => deliver(payment, event, 1), jitter);
    }
  }

  function setStatus(payment, status, reason = null) {
    payment.status = status;
    payment.statusReason = reason;
    payment.updatedAt = new Date().toISOString();
    emit(payment);
  }

  function settle(payment) {
    if (payment.status !== 'ACCP') {
      return false;
    }
    setStatus(payment, 'ACSC');
    return true;
  }

  function authorise(payment, card) {
    later(() => {
      // A cancel may have won the race; never move a payment out of a final state.
      if (payment.status !== 'PDNG') {
        return;
      }
      if (card.outcome === 'ACCP') {
        setStatus(payment, 'ACCP');
        later(() => settle(payment), config.settleAfterMs);
      } else {
        setStatus(payment, 'RJCT', card.reason);
      }
    }, card.slow ? config.slowCallbackMs : config.authDelayMs);
  }

  // ---- http helpers ---------------------------------------------------

  function corsHeaders() {
    return {
      'Access-Control-Allow-Origin': config.allowedOrigin,
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, Idempotency-Key, X-Api-Key',
      'Access-Control-Max-Age': '600',
    };
  }

  function send(res, status, body) {
    const headers = { ...corsHeaders() };
    if (body === undefined) {
      res.writeHead(status, headers);
      res.end();
      return;
    }
    headers['Content-Type'] = 'application/json';
    res.writeHead(status, headers);
    res.end(JSON.stringify(body));
  }

  function problem(res, status, code, message) {
    send(res, status, { code, message });
  }

  function readJson(req) {
    return new Promise((resolve, reject) => {
      const chunks = [];
      let size = 0;
      req.on('data', (chunk) => {
        size += chunk.length;
        if (size > 64 * 1024) {
          reject(new Error('body too large'));
          req.destroy();
          return;
        }
        chunks.push(chunk);
      });
      req.on('end', () => {
        try {
          resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
        } catch (error) {
          reject(new Error('invalid JSON'));
        }
      });
      req.on('error', reject);
    });
  }

  function authorised(req) {
    return !config.apiKey || safeEqual(req.headers['x-api-key'] ?? '', config.apiKey);
  }

  function idempotencyKey(req) {
    const key = req.headers['idempotency-key'];
    return typeof key === 'string' && key.length > 0 && key.length <= 64 ? key : null;
  }

  function publicPayment(payment) {
    return {
      paymentId: payment.paymentId,
      endToEndId: payment.endToEndId,
      amount: payment.amount,
      maskedPan: payment.maskedPan,
      status: payment.status,
      statusReason: payment.statusReason,
      createdAt: payment.createdAt,
      updatedAt: payment.updatedAt,
    };
  }

  // ---- handlers -------------------------------------------------------

  function validPaymentRequest(body) {
    return (
      isPlainObject(body) &&
      typeof body.endToEndId === 'string' && body.endToEndId.length > 0 && body.endToEndId.length <= 35 &&
      isPlainObject(body.amount) &&
      typeof body.amount.value === 'string' && /^\d+\.\d{2}$/.test(body.amount.value) &&
      typeof body.amount.currency === 'string' && /^[A-Z]{3}$/.test(body.amount.currency) &&
      typeof body.token === 'string' && body.token.length > 0 &&
      typeof body.callbackUrl === 'string' && /^https?:\/\/\S+$/.test(body.callbackUrl)
    );
  }

  async function createPayment(req, res) {
    const key = idempotencyKey(req);
    if (!key) {
      return problem(res, 422, 'VALIDATION_ERROR', 'Idempotency-Key header is required (max 64 characters)');
    }
    const body = await readJson(req);
    if (!validPaymentRequest(body)) {
      return problem(res, 422, 'VALIDATION_ERROR', 'Payment request failed validation');
    }

    const hash = sha256(JSON.stringify([
      body.endToEndId, body.amount.value, body.amount.currency, body.token, body.callbackUrl,
    ]));
    const previous = paymentKeys.get(key);
    if (previous) {
      if (previous.hash !== hash) {
        return problem(res, 409, 'IDEMPOTENCY_KEY_REUSED', 'Idempotency key reused with a different request body');
      }
      return send(res, 202, publicPayment(payments.get(previous.paymentId)));
    }

    if (!TOKEN_PATTERN.test(body.token) || usedTokens.has(body.token)) {
      return problem(res, 422, 'INVALID_TOKEN', 'Token is malformed or already used');
    }
    usedTokens.add(body.token);

    const now = new Date().toISOString();
    const payment = {
      paymentId: 'pay_' + crypto.randomBytes(4).toString('hex'),
      endToEndId: body.endToEndId,
      amount: { value: body.amount.value, currency: body.amount.currency },
      maskedPan: MASKED_PAN,
      status: 'PDNG',
      statusReason: null,
      createdAt: now,
      updatedAt: now,
      callbackUrl: body.callbackUrl,
    };
    payments.set(payment.paymentId, payment);
    paymentKeys.set(key, { hash, paymentId: payment.paymentId });
    authorise(payment, outcomeForToken(body.token));
    return send(res, 202, publicPayment(payment));
  }

  function getPayment(res, paymentId) {
    const payment = payments.get(paymentId);
    return payment
      ? send(res, 200, publicPayment(payment))
      : problem(res, 404, 'NOT_FOUND', 'Payment not found');
  }

  function cancelPayment(req, res, paymentId) {
    const key = idempotencyKey(req);
    if (!key) {
      return problem(res, 422, 'VALIDATION_ERROR', 'Idempotency-Key header is required (max 64 characters)');
    }
    const payment = payments.get(paymentId);
    if (!payment) {
      return problem(res, 404, 'NOT_FOUND', 'Payment not found');
    }
    const previous = cancelKeys.get(key);
    if (previous) {
      return previous.paymentId === paymentId
        ? send(res, 200, previous.response)
        : problem(res, 409, 'IDEMPOTENCY_KEY_REUSED', 'Idempotency key reused for a different payment');
    }
    if (payment.status !== 'PDNG' && payment.status !== 'ACCP') {
      return problem(res, 409, 'NOT_CANCELLABLE', 'Payment is already in a final state');
    }
    setStatus(payment, 'CANC');
    const response = publicPayment(payment);
    cancelKeys.set(key, { paymentId, response });
    return send(res, 200, response);
  }

  function forceSettle(res, paymentId) {
    const payment = payments.get(paymentId);
    if (!payment) {
      return problem(res, 404, 'NOT_FOUND', 'Payment not found');
    }
    return settle(payment)
      ? send(res, 200, publicPayment(payment))
      : problem(res, 409, 'NOT_SETTLEABLE', 'Only an authorised payment can be settled');
  }

  // ---- routing --------------------------------------------------------

  async function route(req, res) {
    const path = new URL(req.url, 'http://mock').pathname;
    const { method } = req;

    if (method === 'OPTIONS') {
      return send(res, 204);
    }
    if (method === 'GET' && path === '/health') {
      return send(res, 200, { status: 'UP', service: 'meridian-mock' });
    }

    const settleMatch = path.match(/^\/mock\/payments\/([^/]+)\/settle$/);
    if (method === 'POST' && settleMatch) {
      return forceSettle(res, settleMatch[1]);
    }

    const isPaymentsRoute = path === '/payments' || path.startsWith('/payments/');
    if (isPaymentsRoute && !authorised(req)) {
      return problem(res, 401, 'UNAUTHORIZED', 'Missing or invalid API key');
    }
    if (method === 'POST' && path === '/payments') {
      return createPayment(req, res);
    }
    const cancelMatch = path.match(/^\/payments\/([^/]+)\/cancellation$/);
    if (method === 'POST' && cancelMatch) {
      return cancelPayment(req, res, cancelMatch[1]);
    }
    const getMatch = path.match(/^\/payments\/([^/]+)$/);
    if (method === 'GET' && getMatch) {
      return getPayment(res, getMatch[1]);
    }
    return problem(res, 404, 'NOT_FOUND', 'No such route');
  }

  const server = http.createServer((req, res) => {
    route(req, res)
      .catch((error) => {
        const status = error.message === 'body too large' ? 413 : 422;
        if (!res.headersSent) {
          problem(res, status, 'VALIDATION_ERROR', 'Request body is not valid JSON');
        }
      })
      .finally(() => {
        config.log(`${req.method} ${new URL(req.url, 'http://mock').pathname} -> ${res.statusCode}`);
      });
  });

  function close() {
    closed = true;
    timers.forEach(clearTimeout);
    timers.clear();
    return new Promise((resolve) => {
      server.close(() => resolve());
      server.closeAllConnections();
    });
  }

  return { server, close, config };
}

module.exports = { createMock, configFromEnv, signBody, MAGIC_TOKENS, REASON_MESSAGES };
