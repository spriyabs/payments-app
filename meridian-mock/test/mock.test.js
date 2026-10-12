'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const http = require('node:http');
const { createMock, signBody } = require('../mock');

const SECRET = 'test-secret';
const API_KEY = 'test-key';

/** Local webhook receiver that records every delivery and can fail on demand. */
async function startReceiver() {
  const received = [];
  const failNext = [];
  const server = http.createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      const raw = Buffer.concat(chunks).toString('utf8');
      received.push({ raw, event: JSON.parse(raw), signature: req.headers['x-meridian-signature'] });
      res.writeHead(failNext.length ? failNext.shift() : 200);
      res.end();
    });
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  return {
    received,
    failNext,
    url: `http://127.0.0.1:${server.address().port}/webhooks/meridian`,
    close: () => new Promise((resolve) => { server.close(resolve); server.closeAllConnections(); }),
  };
}

async function setup(overrides = {}) {
  const mock = createMock({
    apiKey: API_KEY,
    webhookSecret: SECRET,
    authDelayMs: 30,
    settleAfterMs: 60 * 60 * 1000,
    slowCallbackMs: 400,
    retryMs: 50,
    log: () => {},
    ...overrides,
  });
  await new Promise((resolve) => mock.server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${mock.server.address().port}`;
  const receiver = await startReceiver();

  const call = async (method, path, { body, headers = {}, auth = true } = {}) => {
    const response = await fetch(base + path, {
      method,
      headers: {
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(auth ? { 'X-Api-Key': API_KEY } : {}),
        ...headers,
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
    const text = await response.text();
    return { status: response.status, headers: response.headers, body: text ? JSON.parse(text) : null, text };
  };

  // Tokenisation is out of scope: tests just invent single-use tokens.
  const newToken = (prefix = 'tok') => `${prefix}_${crypto.randomBytes(4).toString('hex')}`;
  const pay = async ({ key = crypto.randomUUID(), endToEndId = crypto.randomUUID().replace(/-/g, ''), token = newToken() } = {}) => {
    const useToken = token;
    const response = await call('POST', '/payments', {
      headers: { 'Idempotency-Key': key },
      body: { endToEndId, amount: { value: '125.50', currency: 'GBP' }, token: useToken, callbackUrl: receiver.url },
    });
    return { ...response, key, endToEndId, token: useToken };
  };
  const waitFor = async (predicate, ms = 3000) => {
    const deadline = Date.now() + ms;
    while (Date.now() < deadline) {
      if (predicate()) {
        return;
      }
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
    assert.fail('timed out waiting for condition');
  };
  const statuses = (paymentId) => receiver.received.filter((r) => r.event.paymentId === paymentId).map((r) => r.event.status);

  return {
    call, newToken, pay, waitFor, statuses, receiver, mock, base,
    close: async () => { await mock.close(); await receiver.close(); },
  };
}

test('health', async () => {
  const t = await setup();
  try {
    const r = await t.call('GET', '/health', { auth: false });
    assert.equal(r.status, 200);
    assert.equal(r.body.status, 'UP');
  } finally { await t.close(); }
});

test('payments need the API key and an Idempotency-Key', async () => {
  const t = await setup();
  try {
    const noAuth = await t.call('GET', '/payments/pay_x', { auth: false });
    assert.equal(noAuth.status, 401);

    const token = t.newToken();
    const noKey = await t.call('POST', '/payments', {
      body: { endToEndId: 'e1', amount: { value: '1.00', currency: 'GBP' }, token, callbackUrl: t.receiver.url },
    });
    assert.equal(noKey.status, 422);
  } finally { await t.close(); }
});

test('payments: validation rejects bad amounts and malformed tokens', async () => {
  const t = await setup();
  try {
    const token = t.newToken();
    const badAmount = await t.call('POST', '/payments', {
      headers: { 'Idempotency-Key': 'k1' },
      body: { endToEndId: 'e1', amount: { value: '1.5', currency: 'GBP' }, token, callbackUrl: t.receiver.url },
    });
    assert.equal(badAmount.status, 422);

    const malformed = await t.pay({ token: 'not-a-token' });
    assert.equal(malformed.status, 422);
    assert.equal(malformed.body.code, 'INVALID_TOKEN');
  } finally { await t.close(); }
});

test('happy path: PDNG, signed ACCP callback, forced settlement to ACSC', async () => {
  const t = await setup();
  try {
    const created = await t.pay();
    assert.equal(created.status, 202);
    assert.equal(created.body.status, 'PDNG');
    assert.equal(created.body.endToEndId, created.endToEndId);
    assert.match(created.body.paymentId, /^pay_/);

    await t.waitFor(() => t.statuses(created.body.paymentId).includes('ACCP'));
    const delivery = t.receiver.received[0];
    const expected = crypto.createHmac('sha256', SECRET).update(delivery.raw).digest('hex');
    assert.equal(delivery.signature, expected);
    assert.equal(delivery.event.endToEndId, created.endToEndId);
    assert.ok(delivery.event.eventId);
    assert.equal((await t.call('GET', `/payments/${created.body.paymentId}`)).body.status, 'ACCP');

    const settled = await t.call('POST', `/mock/payments/${created.body.paymentId}/settle`, { auth: false });
    assert.equal(settled.status, 200);
    await t.waitFor(() => t.statuses(created.body.paymentId).includes('ACSC'));

    const again = await t.call('POST', `/mock/payments/${created.body.paymentId}/settle`, { auth: false });
    assert.equal(again.status, 409);
  } finally { await t.close(); }
});

test('settles automatically after the configured delay', async () => {
  const t = await setup({ settleAfterMs: 100 });
  try {
    const created = await t.pay();
    await t.waitFor(() => t.statuses(created.body.paymentId).includes('ACSC'));
    assert.deepEqual(t.statuses(created.body.paymentId), ['ACCP', 'ACSC']);
  } finally { await t.close(); }
});

test('tokens are single-use', async () => {
  const t = await setup();
  try {
    const first = await t.pay();
    assert.equal(first.status, 202);
    const second = await t.pay({ token: first.token });
    assert.equal(second.status, 422);
    assert.equal(second.body.code, 'INVALID_TOKEN');
  } finally { await t.close(); }
});

test('idempotency: same key and body replays, different body conflicts', async () => {
  const t = await setup();
  try {
    const first = await t.pay();
    const replay = await t.call('POST', '/payments', {
      headers: { 'Idempotency-Key': first.key },
      body: { endToEndId: first.endToEndId, amount: { value: '125.50', currency: 'GBP' }, token: first.token, callbackUrl: t.receiver.url },
    });
    assert.equal(replay.status, 202);
    assert.equal(replay.body.paymentId, first.body.paymentId);

    const conflict = await t.call('POST', '/payments', {
      headers: { 'Idempotency-Key': first.key },
      body: { endToEndId: first.endToEndId, amount: { value: '999.00', currency: 'GBP' }, token: first.token, callbackUrl: t.receiver.url },
    });
    assert.equal(conflict.status, 409);

    await t.waitFor(() => t.statuses(first.body.paymentId).includes('ACCP'));
    await new Promise((resolve) => setTimeout(resolve, 100));
    assert.equal(t.statuses(first.body.paymentId).length, 1, 'a replay must not create a second authorisation');
  } finally { await t.close(); }
});

test('magic tokens decide the outcome', async () => {
  const t = await setup();
  try {
    const cases = {
      tok_decline_funds: 'AM04',
      tok_decline_blocked: 'AC04',
      tok_decline_invalid: 'FF01',
      tok_decline_other: 'NARR',
    };
    for (const [prefix, reason] of Object.entries(cases)) {
      const created = await t.pay({ token: t.newToken(prefix) });
      await t.waitFor(() => t.statuses(created.body.paymentId).length > 0);
      const delivery = t.receiver.received.find((r) => r.event.paymentId === created.body.paymentId);
      assert.equal(delivery.event.status, 'RJCT');
      assert.equal(delivery.event.statusReason, reason);
    }
  } finally { await t.close(); }
});

test('slow token is authorised after the long callback delay', async () => {
  const t = await setup();
  try {
    const created = await t.pay({ token: t.newToken('tok_slow') });
    await new Promise((resolve) => setTimeout(resolve, 150));
    assert.equal(t.statuses(created.body.paymentId).length, 0);
    await t.waitFor(() => t.statuses(created.body.paymentId).includes('ACCP'));
  } finally { await t.close(); }
});

test('cancel a pending payment: CANC callback, and the later authorisation never reverts it', async () => {
  const t = await setup({ authDelayMs: 200 });
  try {
    const created = await t.pay();
    const cancelled = await t.call('POST', `/payments/${created.body.paymentId}/cancellation`, {
      headers: { 'Idempotency-Key': 'cancel-1' },
    });
    assert.equal(cancelled.status, 200);
    assert.equal(cancelled.body.status, 'CANC');

    await new Promise((resolve) => setTimeout(resolve, 400));
    assert.deepEqual(t.statuses(created.body.paymentId), ['CANC']);
    assert.equal((await t.call('GET', `/payments/${created.body.paymentId}`)).body.status, 'CANC');
  } finally { await t.close(); }
});

test('cancel: replay with the same key returns the same result; settled or rejected payments give 409', async () => {
  const t = await setup();
  try {
    const created = await t.pay();
    await t.waitFor(() => t.statuses(created.body.paymentId).includes('ACCP'));
    const id = created.body.paymentId;

    const first = await t.call('POST', `/payments/${id}/cancellation`, { headers: { 'Idempotency-Key': 'c1' } });
    const replay = await t.call('POST', `/payments/${id}/cancellation`, { headers: { 'Idempotency-Key': 'c1' } });
    assert.equal(first.status, 200);
    assert.equal(replay.status, 200);
    assert.deepEqual(replay.body, first.body);

    const other = await t.call('POST', `/payments/${id}/cancellation`, { headers: { 'Idempotency-Key': 'c2' } });
    assert.equal(other.status, 409);

    const settled = await t.pay();
    await t.waitFor(() => t.statuses(settled.body.paymentId).includes('ACCP'));
    await t.call('POST', `/mock/payments/${settled.body.paymentId}/settle`, { auth: false });
    const tooLate = await t.call('POST', `/payments/${settled.body.paymentId}/cancellation`, { headers: { 'Idempotency-Key': 'c3' } });
    assert.equal(tooLate.status, 409);

    const rejected = await t.pay({ token: t.newToken('tok_decline_funds') });
    await t.waitFor(() => t.statuses(rejected.body.paymentId).includes('RJCT'));
    const noWay = await t.call('POST', `/payments/${rejected.body.paymentId}/cancellation`, { headers: { 'Idempotency-Key': 'c4' } });
    assert.equal(noWay.status, 409);

    const missing = await t.call('POST', '/payments/pay_nope/cancellation', { headers: { 'Idempotency-Key': 'c5' } });
    assert.equal(missing.status, 404);
  } finally { await t.close(); }
});

test('chaos: duplicate webhooks carry the same eventId', async () => {
  const t = await setup({ duplicateWebhooks: true });
  try {
    const created = await t.pay();
    await t.waitFor(() => t.statuses(created.body.paymentId).length >= 2);
    const [a, b] = t.receiver.received;
    assert.equal(a.event.eventId, b.event.eventId);
    assert.equal(a.raw, b.raw);
  } finally { await t.close(); }
});

test('failed deliveries are retried with the same event', async () => {
  const t = await setup();
  try {
    t.receiver.failNext.push(500, 500);
    const created = await t.pay();
    await t.waitFor(() => t.statuses(created.body.paymentId).length >= 3);
    const ids = new Set(t.receiver.received.map((r) => r.event.eventId));
    assert.equal(ids.size, 1);
  } finally { await t.close(); }
});

test('signature format matches the shared test vector used by the Java API', () => {
  // The same secret, body and signature are asserted in WebhookSignatureVerifierTest.java.
  const body = '{"eventId":"evt_1","paymentId":"pay_1","endToEndId":"abc","status":"ACCP",'
    + '"statusReason":null,"occurredAt":"2026-10-09T10:00:00Z"}';
  assert.equal(
    signBody('test-secret', body),
    '34dd798f9ad93a8edffda420aaa5a6991ebe8e90270688fad43325b48bf42435',
  );
});
