import test from 'node:test';
import assert from 'node:assert/strict';
import { ApiError, createApi } from '../src/api.mjs';

function fakeFetch(status, body, { text } = {}) {
  const calls = [];
  const fn = async (url, init) => {
    calls.push({ url, init });
    return { ok: status >= 200 && status < 300, status, text: async () => text ?? (body === undefined ? '' : JSON.stringify(body)) };
  };
  fn.calls = calls;
  return fn;
}

test('submitPayment sends the token, amount and currency with the Idempotency-Key', async () => {
  const fetchFn = fakeFetch(202, { id: 'p1', status: 'PDNG' });
  const api = createApi('http://api.test/', fetchFn);

  const payment = await api.submitPayment({ token: 'tok_1', amount: '125.50', currency: 'GBP' }, 'key-1');

  assert.equal(payment.id, 'p1');
  const { url, init } = fetchFn.calls[0];
  assert.equal(url, 'http://api.test/api/payments');
  assert.equal(init.method, 'POST');
  assert.equal(init.headers['Idempotency-Key'], 'key-1');
  assert.equal(init.headers['Content-Type'], 'application/json');
  assert.deepEqual(JSON.parse(init.body), { token: 'tok_1', amount: { value: '125.50', currency: 'GBP' } });
});

test('getPayment, cancelPayment and listCurrencies use the documented paths', async () => {
  const fetchFn = fakeFetch(200, {});
  const api = createApi('http://api.test', fetchFn);
  await api.getPayment('a b');
  await api.cancelPayment('p1');
  await api.listCurrencies();
  assert.deepEqual(fetchFn.calls.map((c) => [c.init.method, c.url]), [
    ['GET', 'http://api.test/api/payments/a%20b'],
    ['POST', 'http://api.test/api/payments/p1/cancellation'],
    ['GET', 'http://api.test/api/currencies'],
  ]);
  assert.equal(fetchFn.calls[1].init.body, undefined);
});

test('problem responses become an ApiError with the stable code and the payment', async () => {
  const body = { code: 'PAYMENT_NOT_CANCELLABLE', detail: 'raw detail', payment: { id: 'p1', status: 'ACSC' } };
  const api = createApi('http://api.test', fakeFetch(409, body));

  await assert.rejects(api.cancelPayment('p1'), (error) => {
    assert.ok(error instanceof ApiError);
    assert.equal(error.status, 409);
    assert.equal(error.code, 'PAYMENT_NOT_CANCELLABLE');
    assert.equal(error.payment.status, 'ACSC');
    assert.ok(!error.message.includes('raw detail'), 'server detail text is not carried into the error');
    return true;
  });
});

test('a non-JSON error body still gives an ApiError', async () => {
  const api = createApi('http://api.test', fakeFetch(502, undefined, { text: '<html>Bad gateway</html>' }));
  await assert.rejects(api.getPayment('p1'), (error) => error.status === 502 && error.code === 'UNKNOWN');
});

test('a network failure becomes status 0, and an abort is passed through untouched', async () => {
  const down = createApi('http://api.test', async () => { throw new TypeError('fetch failed'); });
  await assert.rejects(down.getPayment('p1'), (error) => error.status === 0 && error.code === 'NETWORK_ERROR');

  const aborted = createApi('http://api.test', async () => { throw Object.assign(new Error('x'), { name: 'AbortError' }); });
  await assert.rejects(aborted.getPayment('p1'), (error) => error.name === 'AbortError');
});
