// Calls to the Payments API. Plain fetch, no framework, no logging of request bodies.

export class ApiError extends Error {
  constructor(status, code, payment = null) {
    super(`${status} ${code}`);
    this.name = 'ApiError';
    this.status = status; // 0 means the request never got an answer
    this.code = code; // stable error code from the API, or NETWORK_ERROR / UNKNOWN
    this.payment = payment; // only set for PAYMENT_NOT_CANCELLABLE
  }
}

export function createApi(baseUrl, fetchFn = (...args) => fetch(...args)) {
  const root = baseUrl.replace(/\/+$/, '');

  async function request(path, { method = 'GET', headers = {}, body, signal } = {}) {
    let response;
    try {
      response = await fetchFn(root + path, {
        method,
        headers: {
          Accept: 'application/json',
          ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
          ...headers,
        },
        body: body !== undefined ? JSON.stringify(body) : undefined,
        signal,
      });
    } catch (error) {
      if (error?.name === 'AbortError') {
        throw error;
      }
      throw new ApiError(0, 'NETWORK_ERROR');
    }
    const text = await response.text();
    let data = null;
    try {
      data = text ? JSON.parse(text) : null;
    } catch {
      data = null;
    }
    if (!response.ok) {
      throw new ApiError(response.status, data?.code ?? 'UNKNOWN', data?.payment ?? null);
    }
    return data;
  }

  return {
    listCurrencies: (signal) => request('/api/currencies', { signal }),

    /** The same key must be reused when repeating a request after a failure. */
    submitPayment: ({ token, amount, currency }, idempotencyKey, signal) =>
      request('/api/payments', {
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
        body: { token, amount: { value: amount, currency } },
        signal,
      }),

    getPayment: (id, signal) => request(`/api/payments/${encodeURIComponent(id)}`, { signal }),

    cancelPayment: (id, signal) =>
      request(`/api/payments/${encodeURIComponent(id)}/cancellation`, { method: 'POST', signal }),
  };
}
