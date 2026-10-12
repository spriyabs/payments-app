// Pure polling rules (spec section 9.1). No React, no timers: easy to test.

export const FINAL_STATUSES = ['ACSC', 'RJCT', 'CANC'];
export const CONNECTION_PROBLEM_AFTER = 5;

/** How long a payment may stay in a status before we stop polling on our own. */
export const STALL_AFTER_MS = { PDNG: 5 * 60_000, ACCP: 15 * 60_000 };

export function isFinal(status) {
  return FINAL_STATUSES.includes(status);
}

/** Interval before jitter. elapsedMs is the time the payment has been in its current status. */
export function baseDelayMs(status, elapsedMs) {
  if (status === 'ACCP') {
    return 10_000;
  }
  if (elapsedMs < 10_000) {
    return 1_000;
  }
  if (elapsedMs < 60_000) {
    return 2_000;
  }
  return 5_000;
}

/** Plus or minus 20%, spread by random() in [0, 1]. */
export function withJitter(ms, random = Math.random) {
  return Math.round(ms * (0.8 + 0.4 * random()));
}

/** Failure backoff: 2s, 4s, 8s, 16s, then 30s at most. */
export function backoffDelayMs(failures) {
  return Math.min(2_000 * 2 ** (failures - 1), 30_000);
}

export function nextDelayMs({ status, elapsedMs, failures }, random = Math.random) {
  if (failures > 0) {
    return backoffDelayMs(failures);
  }
  return withJitter(baseDelayMs(status, elapsedMs), random);
}

export function isStalled(status, elapsedMs) {
  return elapsedMs >= (STALL_AFTER_MS[status] ?? Infinity);
}

export function hasConnectionProblem(failures) {
  return failures >= CONNECTION_PROBLEM_AFTER;
}
