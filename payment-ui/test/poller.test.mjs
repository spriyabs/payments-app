import test from 'node:test';
import assert from 'node:assert/strict';
import { createPoller } from '../src/poller.mjs';

/** Fake clock and timers: nothing runs until advance() is called. */
function fakeEnv() {
  let time = 0;
  let nextId = 1;
  let hidden = false;
  const timers = new Map();
  return {
    env: {
      now: () => time,
      random: () => 0.5, // no jitter, so intervals are exact
      isHidden: () => hidden,
      setTimeout: (fn, ms) => {
        const id = nextId++;
        timers.set(id, { fn, at: time + ms });
        return id;
      },
      clearTimeout: (id) => timers.delete(id),
    },
    pending: () => [...timers.values()].map((t) => t.at - time),
    setHidden: (value) => { hidden = value; },
    /** Moves the clock forward, firing due timers in order, letting async work settle after each. */
    async advance(ms) {
      const target = time + ms;
      for (;;) {
        const due = [...timers.entries()].filter(([, t]) => t.at <= target).sort((a, b) => a[1].at - b[1].at)[0];
        if (!due) break;
        timers.delete(due[0]);
        time = due[1].at;
        due[1].fn();
        await settle();
      }
      time = target;
    },
  };
}

const settle = () => new Promise((resolve) => setImmediate(resolve));
const payment = (status, extra = {}) => ({ id: 'p1', status, amount: { value: '1.00', currency: 'GBP' }, ...extra });

/** Fake API: each call takes the next scripted answer (a payment, an Error, or a function). */
function fakeApi(script) {
  const calls = [];
  return {
    calls,
    getPayment: (id, signal) => {
      calls.push({ id, signal });
      const next = script.length > 1 ? script.shift() : script[0];
      if (typeof next === 'function') return next(signal);
      return next instanceof Error ? Promise.reject(next) : Promise.resolve(next);
    },
  };
}

function setup(script, envOverrides = {}) {
  const clock = fakeEnv();
  const api = fakeApi(script);
  const seen = { payments: [], problems: [], failures: [] };
  const poller = createPoller({
    api,
    paymentId: 'p1',
    onPayment: (p) => seen.payments.push(p.status),
    onProblem: (p) => seen.problems.push(p),
    onFailures: (n) => seen.failures.push(n),
    env: { ...clock.env, ...envOverrides },
  });
  return { clock, api, seen, poller };
}

test('F1: PDNG then ACCP then ACSC updates the view and stops at ACSC', async () => {
  const { clock, api, seen, poller } = setup([payment('PDNG'), payment('ACCP'), payment('ACSC')]);
  poller.start();
  await settle();
  await clock.advance(1_000);
  await clock.advance(10_000);
  assert.deepEqual(seen.payments, ['PDNG', 'ACCP', 'ACSC']);
  assert.deepEqual(clock.pending(), []);
  await clock.advance(60_000);
  assert.equal(api.calls.length, 3);
});

test('F2: intervals are 1s, 2s, 5s for PDNG and 10s for ACCP', async () => {
  const { clock, api, poller } = setup([payment('PDNG')]);
  poller.start();
  await settle();
  const intervals = [];
  for (let i = 0; i < 40; i += 1) {
    const [delay] = clock.pending();
    intervals.push(delay);
    await clock.advance(delay);
  }
  assert.deepEqual(intervals.slice(0, 10), Array(10).fill(1_000));
  assert.deepEqual(intervals.slice(10, 35), Array(25).fill(2_000)); // 10s to 60s
  assert.deepEqual(intervals.slice(35), Array(5).fill(5_000));
  assert.equal(api.calls.length, 41);

  const accp = setup([payment('ACCP')]);
  accp.poller.start();
  await settle();
  assert.deepEqual(accp.clock.pending(), [10_000]);
});

test('F3: no requests while the tab is hidden, one immediate poll on return', async () => {
  const hidden = { value: false };
  const { clock, api, poller } = setup([payment('PDNG')], { isHidden: () => hidden.value });
  poller.start();
  await settle();
  assert.equal(api.calls.length, 1);

  hidden.value = true;
  poller.visibilityChanged();
  await clock.advance(60_000);
  assert.equal(api.calls.length, 1, 'nothing may be requested while hidden');

  hidden.value = false;
  poller.visibilityChanged();
  await settle();
  assert.equal(api.calls.length, 2, 'one immediate poll when visible again');
});

test('F4: repeated failures back off to 30s and show a connection problem after 5', async () => {
  const { clock, seen, poller } = setup([new Error('boom')]);
  poller.start();
  await settle();
  const delays = [];
  for (let i = 0; i < 7; i += 1) {
    const [delay] = clock.pending();
    delays.push(delay);
    await clock.advance(delay);
  }
  assert.deepEqual(delays, [2_000, 4_000, 8_000, 16_000, 30_000, 30_000, 30_000]);
  assert.deepEqual(seen.failures.slice(0, 5), [1, 2, 3, 4, 5]);
});

test('F4: a success after failures resets the counter', async () => {
  const failing = Object.assign(new Error('server'), { status: 500 });
  const { clock, seen, poller } = setup([failing, failing, payment('ACCP')]);
  poller.start();
  await settle();
  await clock.advance(2_000);
  await clock.advance(4_000);
  assert.deepEqual(seen.failures, [1, 2, 0]);
  assert.deepEqual(seen.payments, ['ACCP']);
});

test('F5: after 5 minutes in PDNG polling stops and reports stalled; refresh starts again', async () => {
  const { clock, api, seen, poller } = setup([payment('PDNG')]);
  poller.start();
  await settle();
  await clock.advance(5 * 60_000 + 10_000);
  assert.deepEqual(seen.problems, ['stalled']);
  assert.deepEqual(clock.pending(), []);
  const callsWhenStalled = api.calls.length;

  await clock.advance(60_000);
  assert.equal(api.calls.length, callsWhenStalled);

  poller.refresh();
  await settle();
  assert.equal(api.calls.length, callsWhenStalled + 1);
  assert.equal(seen.problems.at(-1), null);
  assert.equal(clock.pending().length, 1);
});

test('ACCP stalls only after 15 minutes', async () => {
  const { clock, seen, poller } = setup([payment('ACCP')]);
  poller.start();
  await settle();
  await clock.advance(14 * 60_000);
  assert.deepEqual(seen.problems, []);
  await clock.advance(2 * 60_000);
  assert.deepEqual(seen.problems, ['stalled']);
});

test('F6: stopping mid-request aborts it and ignores the late answer', async () => {
  let release;
  let signal;
  const slow = (s) => new Promise((resolve) => { signal = s; release = () => resolve(payment('ACSC')); });
  const { seen, poller } = setup([slow]);
  poller.start();
  await settle();
  poller.stop();
  assert.equal(signal.aborted, true);
  release();
  await settle();
  assert.deepEqual(seen.payments, []);
});

test('a 404 stops polling and reports notFound', async () => {
  const { clock, seen, poller } = setup([Object.assign(new Error('nf'), { status: 404 })]);
  poller.start();
  await settle();
  assert.deepEqual(seen.problems, ['notFound']);
  assert.deepEqual(clock.pending(), []);
});

test('a cancel result applied from outside wins over an in-flight poll', async () => {
  let release;
  let signal;
  const slow = (s) => new Promise((resolve) => { signal = s; release = () => resolve(payment('PDNG')); });
  const { clock, seen, poller } = setup([slow]);
  poller.start();
  await settle();
  poller.apply(payment('CANC'));
  assert.equal(signal.aborted, true);
  release();
  await settle();
  assert.deepEqual(seen.payments, ['CANC'], 'the stale PDNG answer must not undo the cancel');
  assert.deepEqual(clock.pending(), []);
});

test('requests never overlap: the next poll is scheduled only after the answer', async () => {
  let release;
  const slow = () => new Promise((resolve) => { release = () => resolve(payment('PDNG')); });
  const { clock, api, poller } = setup([slow]);
  poller.start();
  await settle();
  assert.deepEqual(clock.pending(), []);
  await clock.advance(30_000);
  assert.equal(api.calls.length, 1);
  release();
  await settle();
  assert.equal(clock.pending().length, 1);
});
