import test from 'node:test';
import assert from 'node:assert/strict';
import {
  backoffDelayMs, baseDelayMs, hasConnectionProblem, isFinal, isStalled, nextDelayMs, withJitter,
} from '../src/polling.mjs';

test('PDNG interval: 1s for the first 10s, 2s until 60s, then 5s', () => {
  assert.equal(baseDelayMs('PDNG', 0), 1_000);
  assert.equal(baseDelayMs('PDNG', 9_999), 1_000);
  assert.equal(baseDelayMs('PDNG', 10_000), 2_000);
  assert.equal(baseDelayMs('PDNG', 59_999), 2_000);
  assert.equal(baseDelayMs('PDNG', 60_000), 5_000);
  assert.equal(baseDelayMs('PDNG', 4 * 60_000), 5_000);
});

test('ACCP interval is 10s whatever the elapsed time', () => {
  assert.equal(baseDelayMs('ACCP', 0), 10_000);
  assert.equal(baseDelayMs('ACCP', 600_000), 10_000);
});

test('jitter stays within plus or minus 20%', () => {
  assert.equal(withJitter(1_000, () => 0), 800);
  assert.equal(withJitter(1_000, () => 0.5), 1_000);
  assert.equal(withJitter(1_000, () => 1), 1_200);
  for (let i = 0; i < 200; i += 1) {
    const ms = withJitter(10_000);
    assert.ok(ms >= 8_000 && ms <= 12_000);
  }
});

test('failure backoff doubles from 2s and caps at 30s', () => {
  assert.deepEqual([1, 2, 3, 4, 5, 6, 7, 20].map(backoffDelayMs), [2_000, 4_000, 8_000, 16_000, 30_000, 30_000, 30_000, 30_000]);
});

test('nextDelayMs uses backoff when polls are failing and jittered intervals otherwise', () => {
  assert.equal(nextDelayMs({ status: 'PDNG', elapsedMs: 0, failures: 3 }, () => 0), 8_000);
  assert.equal(nextDelayMs({ status: 'PDNG', elapsedMs: 0, failures: 0 }, () => 0.5), 1_000);
  assert.equal(nextDelayMs({ status: 'ACCP', elapsedMs: 0, failures: 0 }, () => 0.5), 10_000);
});

test('stall limits: 5 minutes in PDNG, 15 minutes in ACCP, never for final states', () => {
  assert.equal(isStalled('PDNG', 5 * 60_000 - 1), false);
  assert.equal(isStalled('PDNG', 5 * 60_000), true);
  assert.equal(isStalled('ACCP', 14 * 60_000), false);
  assert.equal(isStalled('ACCP', 15 * 60_000), true);
  assert.equal(isStalled('ACSC', 99 * 60_000), false);
});

test('final statuses and the connection problem threshold', () => {
  assert.deepEqual(['PDNG', 'ACCP', 'ACSC', 'RJCT', 'CANC'].map(isFinal), [false, false, true, true, true]);
  assert.equal(hasConnectionProblem(4), false);
  assert.equal(hasConnectionProblem(5), true);
});
