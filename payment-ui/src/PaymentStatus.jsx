import { useEffect, useRef, useState } from 'react';
import { usePaymentPolling } from './usePaymentPolling.js';
import { hasConnectionProblem } from './polling.mjs';
import { canCancel, describeCancelError, formatAmount, reasonText, viewFor } from './status.mjs';

/** Shows one payment and keeps it up to date. Never shows card data or the token. */
export default function PaymentStatus({ api, paymentId, initialPayment, onNewPayment }) {
  const { payment, problem, failures, refresh, applyPayment } = usePaymentPolling(api, paymentId, initialPayment);
  const [busy, setBusy] = useState(false);
  const [cancelMessage, setCancelMessage] = useState(null);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  async function cancel() {
    setBusy(true);
    setCancelMessage(null);
    try {
      applyPayment(await api.cancelPayment(paymentId));
    } catch (error) {
      if (error?.code === 'PAYMENT_NOT_CANCELLABLE' && error.payment) {
        applyPayment(error.payment); // refresh the badge from the server's view
      }
      if (mounted.current) {
        setCancelMessage(describeCancelError(error));
      }
    } finally {
      if (mounted.current) {
        setBusy(false);
      }
    }
  }

  if (problem === 'notFound') {
    return (
      <section>
        <h1>Payment not found</h1>
        <p>We couldn't find that payment.</p>
        <button type="button" className="primary" onClick={() => onNewPayment()}>New payment</button>
      </section>
    );
  }

  if (!payment) {
    return (
      <section aria-busy="true">
        <h1>Payment</h1>
        <p>
          <span className="spinner" aria-hidden="true" /> Loading…
        </p>
        {hasConnectionProblem(failures) && <p className="banner warn" role="status">Connection problem, retrying</p>}
      </section>
    );
  }

  const view = viewFor(payment.status);
  const message = payment.status === 'RJCT' ? reasonText(payment.statusReason) : view.message;

  return (
    <section>
      <h1>Payment</h1>
      <p className={`badge ${view.tone}`} role="status" aria-live="polite">
        {view.spinner && <span className="spinner" aria-hidden="true" />}
        {view.tick && <span aria-hidden="true">✓ </span>}
        {view.label}
      </p>
      {message && <p className="message">{message}</p>}

      <dl>
        <dt>Amount</dt>
        <dd>{formatAmount(payment.amount)}</dd>
        <dt>Payment ID</dt>
        <dd className="mono">{payment.id}</dd>
      </dl>

      {cancelMessage && <p className="banner error" role="alert">{cancelMessage}</p>}
      {hasConnectionProblem(failures) && <p className="banner warn" role="status">Connection problem, retrying</p>}
      {problem === 'stalled' && (
        <p className="banner warn" role="status">
          Still processing, you can safely close this page.{' '}
          <button type="button" className="link" onClick={refresh}>Refresh status</button>
        </p>
      )}

      <div className="actions">
        {canCancel(payment) && (
          <button type="button" onClick={cancel} disabled={busy}>
            {busy ? 'Cancelling…' : 'Cancel payment'}
          </button>
        )}
        {payment.status === 'RJCT' && (
          <button type="button" className="primary" onClick={() => onNewPayment({ amount: payment.amount.value, currency: payment.amount.currency })}>
            Try again
          </button>
        )}
        {(payment.status === 'CANC' || payment.status === 'ACSC') && (
          <button type="button" className="primary" onClick={() => onNewPayment()}>New payment</button>
        )}
      </div>
    </section>
  );
}
