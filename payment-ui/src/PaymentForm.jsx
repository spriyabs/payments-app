import { useEffect, useRef, useState } from 'react';
import { describeSubmitError, newToken, validateAmount, validateToken } from './status.mjs';

/**
 * Token, amount and currency. The Idempotency-Key is created on the first submit and kept
 * until a field changes, so "Try again" after a failure repeats the SAME request and key.
 */
export default function PaymentForm({ api, prefill, onSubmitted }) {
  const [token, setToken] = useState(() => newToken());
  const [amount, setAmount] = useState(prefill?.amount ?? '');
  const [currency, setCurrency] = useState(prefill?.currency ?? '');
  const [currencies, setCurrencies] = useState(null);
  const [currencyError, setCurrencyError] = useState(false);
  const [errors, setErrors] = useState({});
  const [message, setMessage] = useState(null);
  const [canRetry, setCanRetry] = useState(false);
  const [busy, setBusy] = useState(false);
  const keyRef = useRef(null);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    api
      .listCurrencies(controller.signal)
      .then((list) => {
        setCurrencies(list);
        setCurrency((current) => current || list[0]?.code || '');
      })
      .catch((error) => {
        if (error?.name !== 'AbortError') {
          setCurrencyError(true);
        }
      });
    return () => controller.abort();
  }, [api]);

  // Any edit makes it a different request, so it gets a new key.
  function edit(setter) {
    return (event) => {
      keyRef.current = null;
      setCanRetry(false);
      setMessage(null);
      setter(event.target.value);
    };
  }

  async function submit(event) {
    event.preventDefault();
    const found = {
      token: validateToken(token),
      amount: validateAmount(amount),
      currency: currency ? null : 'Pick a currency',
    };
    setErrors(found);
    if (found.token || found.amount || found.currency) {
      return;
    }

    keyRef.current ??= globalThis.crypto.randomUUID();
    setBusy(true);
    setMessage(null);
    try {
      const payment = await api.submitPayment({ token, amount, currency }, keyRef.current);
      if (mounted.current) {
        onSubmitted(payment, { amount, currency });
      }
    } catch (error) {
      if (!mounted.current) {
        return;
      }
      const outcome = describeSubmitError(error);
      setMessage(outcome.message);
      setCanRetry(Boolean(outcome.retrySameRequest));
      if (outcome.newToken) {
        setToken(newToken());
        keyRef.current = null;
      }
      if (outcome.newKey) {
        keyRef.current = null;
      }
    } finally {
      if (mounted.current) {
        setBusy(false);
      }
    }
  }

  return (
    <form onSubmit={submit} noValidate>
      <h1>New payment</h1>

      <label htmlFor="token">Card token</label>
      <input
        id="token"
        value={token}
        onChange={edit(setToken)}
        autoComplete="off"
        spellCheck="false"
        aria-invalid={Boolean(errors.token)}
        aria-describedby="token-help"
      />
      <p id="token-help" className="help">
        Card details are turned into a single-use token before they reach this service, so a made-up one is filled in.
      </p>
      {errors.token && <p className="field-error" role="alert">{errors.token}</p>}

      <div className="row">
        <div>
          <label htmlFor="amount">Amount</label>
          <input
            id="amount"
            value={amount}
            onChange={edit(setAmount)}
            inputMode="decimal"
            placeholder="125.50"
            autoComplete="off"
            aria-invalid={Boolean(errors.amount)}
          />
        </div>
        <div>
          <label htmlFor="currency">Currency</label>
          <select id="currency" value={currency} onChange={edit(setCurrency)} disabled={!currencies}>
            {!currencies && <option value="">Loading…</option>}
            {currencies?.map((item) => (
              <option key={item.code} value={item.code}>{item.code}</option>
            ))}
          </select>
        </div>
      </div>
      {errors.amount && <p className="field-error" role="alert">{errors.amount}</p>}
      {errors.currency && <p className="field-error" role="alert">{errors.currency}</p>}
      {currencyError && <p className="field-error" role="alert">We couldn't load the currencies. Reload the page to try again.</p>}

      {message && <p className="banner error" role="alert">{message}</p>}

      <button type="submit" className="primary" disabled={busy || !currencies}>
        {busy ? 'Submitting…' : canRetry ? 'Try again' : 'Pay'}
      </button>
    </form>
  );
}
