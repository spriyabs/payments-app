// What the page shows for each status, error and input. Never shows raw provider or server errors.

export const STATUS_VIEW = {
  PDNG: { label: 'Processing', tone: 'grey', spinner: true, message: 'Waiting for your bank' },
  ACCP: { label: 'Authorised', tone: 'green', message: 'Approved, settles overnight' },
  ACSC: { label: 'Settled', tone: 'green', tick: true, message: 'Payment complete' },
  RJCT: { label: 'Declined', tone: 'red', message: null },
  CANC: { label: 'Cancelled', tone: 'grey', message: 'This payment was cancelled' },
};

export const DECLINE_REASONS = {
  AM04: 'Insufficient funds',
  AC04: 'Card is closed or blocked',
  FF01: 'Card details are invalid',
  NARR: 'Payment could not be completed',
};

export function reasonText(code) {
  return DECLINE_REASONS[code] ?? DECLINE_REASONS.NARR;
}

export function viewFor(status) {
  return STATUS_VIEW[status] ?? { label: status, tone: 'grey', message: null };
}

/** Only these should offer Cancel (same rule as the API). */
export function canCancel(payment) {
  return payment.status === 'PDNG' || payment.status === 'ACCP';
}

export function formatAmount(amount) {
  return `${amount.value} ${amount.currency}`;
}

/** Tokenisation is out of scope, so the page suggests a made-up single-use token. */
export function newToken() {
  return `tok_${globalThis.crypto.randomUUID().replaceAll('-', '').slice(0, 12)}`;
}

/** Returns an error message, or null when the amount is fine. Same shape the API accepts. */
export function validateAmount(value) {
  if (!/^\d+\.\d{2}$/.test(value)) {
    return 'Enter an amount with exactly two decimals, for example 125.50';
  }
  if (Number(value) <= 0) {
    return 'The amount must be greater than zero';
  }
  return null;
}

export function validateToken(value) {
  return /^tok_[A-Za-z0-9_-]{1,60}$/.test(value) ? null : 'Enter a token that starts with tok_';
}

/**
 * Submit failures. retrySameRequest means the same request and key can safely be repeated;
 * newToken means the token is spent and a fresh one is needed.
 */
export function describeSubmitError(error) {
  const code = error?.code;
  if (error?.status === 0 || error?.status === 502 || code === 'PROVIDER_UNAVAILABLE') {
    return { message: "We couldn't submit your payment. Please try again.", retrySameRequest: true };
  }
  if (code === 'PROVIDER_REJECTED') {
    return {
      message: 'The payment provider rejected this token. Tokens can only be used once, so a new one is filled in.',
      newToken: true,
    };
  }
  if (code === 'INVALID_AMOUNT') {
    return { message: 'The amount is not allowed. Enter an amount above zero and within the limit.' };
  }
  if (code === 'CURRENCY_NOT_SUPPORTED') {
    return { message: 'That currency is not supported. Pick another one.' };
  }
  if (code === 'IDEMPOTENCY_KEY_CONFLICT') {
    return { message: 'This request clashes with an earlier one. Change a detail and try again.', newKey: true };
  }
  if (code === 'VALIDATION_ERROR') {
    return { message: 'Please check the details and try again.' };
  }
  return { message: 'Something went wrong. Please try again.', retrySameRequest: true };
}

export function describeCancelError(error) {
  if (error?.code === 'PAYMENT_NOT_CANCELLABLE') {
    return 'This payment can no longer be cancelled';
  }
  return "We couldn't cancel the payment. Please try again.";
}
