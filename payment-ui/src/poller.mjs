import { isFinal, isStalled, nextDelayMs } from './polling.mjs';

/**
 * Polls one payment following spec section 9.1. Framework free so it can be tested with
 * fake timers; usePaymentPolling.js wraps it for React.
 *
 * - Requests never overlap: the next poll is scheduled only after the previous one ends.
 * - Hidden tab: nothing runs; one immediate poll when the tab is visible again.
 * - Aborting (stop, hidden tab, a cancel result) makes any in-flight response stale and ignored.
 *
 * Callbacks: onPayment(payment), onProblem('notFound' | 'stalled' | null), onFailures(count).
 * env is only for tests: setTimeout, clearTimeout, now, random, isHidden.
 */
export function createPoller({ api, paymentId, onPayment, onProblem, onFailures, env = {} }) {
  const setT = env.setTimeout ?? ((fn, ms) => setTimeout(fn, ms));
  const clearT = env.clearTimeout ?? ((id) => clearTimeout(id));
  const now = env.now ?? (() => Date.now());
  const random = env.random ?? Math.random;
  const isHidden = env.isHidden ?? (() => false);

  let timer = null;
  let controller = null;
  let stopped = false;
  let finished = false;
  let failures = 0;
  let sinceStatus = null;
  let sinceAt = now();

  function cancelWork() {
    if (timer !== null) {
      clearT(timer);
      timer = null;
    }
    if (controller) {
      controller.abort();
      controller = null;
    }
  }

  async function poll() {
    timer = null;
    if (stopped || finished || isHidden()) {
      return;
    }
    const mine = new AbortController();
    controller = mine;
    try {
      const payment = await api.getPayment(paymentId, mine.signal);
      if (stopped || mine.signal.aborted) {
        return;
      }
      controller = null;
      if (failures > 0) {
        failures = 0;
        onFailures(0);
      }
      if (payment.status !== sinceStatus) {
        sinceStatus = payment.status;
        sinceAt = now();
      }
      onPayment(payment);
      if (isFinal(payment.status)) {
        finished = true;
        return;
      }
      const elapsedMs = now() - sinceAt;
      if (isStalled(payment.status, elapsedMs)) {
        finished = true;
        onProblem('stalled');
        return;
      }
      timer = setT(poll, nextDelayMs({ status: payment.status, elapsedMs, failures: 0 }, random));
    } catch (error) {
      if (stopped || mine.signal.aborted || error?.name === 'AbortError') {
        return;
      }
      controller = null;
      if (error?.status === 404) {
        finished = true;
        onProblem('notFound');
        return;
      }
      failures += 1;
      onFailures(failures);
      timer = setT(poll, nextDelayMs({ status: sinceStatus ?? 'PDNG', elapsedMs: 0, failures }, random));
    }
  }

  return {
    /** First poll happens immediately. */
    start() {
      poll();
    },
    stop() {
      stopped = true;
      cancelWork();
    },
    /** A payment we learned about elsewhere (for example the cancel response). */
    apply(payment) {
      if (stopped) {
        return;
      }
      onPayment(payment);
      if (isFinal(payment.status)) {
        finished = true;
        cancelWork();
      }
    },
    /** "Refresh status": start again with fresh timing. */
    refresh() {
      if (stopped) {
        return;
      }
      cancelWork();
      finished = false;
      failures = 0;
      sinceStatus = null;
      sinceAt = now();
      onFailures(0);
      onProblem(null);
      poll();
    },
    /** Call when document.hidden changes. */
    visibilityChanged() {
      if (stopped) {
        return;
      }
      if (isHidden()) {
        cancelWork();
      } else if (!finished && timer === null && controller === null) {
        poll();
      }
    },
  };
}
