import { useCallback, useEffect, useMemo, useState } from 'react';
import { createApi } from './api.mjs';
import PaymentForm from './PaymentForm.jsx';
import PaymentStatus from './PaymentStatus.jsx';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** The payment id lives in the URL (?payment=...) so a refresh resumes tracking. */
function paymentIdFromUrl() {
  const id = new URLSearchParams(window.location.search).get('payment');
  return id && UUID.test(id) ? id : null;
}

export default function App({ config }) {
  const api = useMemo(() => createApi(config.apiUrl), [config.apiUrl]);
  const [paymentId, setPaymentId] = useState(paymentIdFromUrl);
  const [justSubmitted, setJustSubmitted] = useState(null);
  const [prefill, setPrefill] = useState(null);

  useEffect(() => {
    const onPop = () => {
      setJustSubmitted(null);
      setPaymentId(paymentIdFromUrl());
    };
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
  }, []);

  const handleSubmitted = useCallback((payment, request) => {
    window.history.pushState(null, '', `?payment=${payment.id}`);
    setPrefill(request);
    setJustSubmitted(payment);
    setPaymentId(payment.id);
  }, []);

  const handleNewPayment = useCallback((nextPrefill = null) => {
    window.history.pushState(null, '', window.location.pathname);
    setJustSubmitted(null);
    setPrefill(nextPrefill);
    setPaymentId(null);
  }, []);

  return (
    <main>
      {paymentId ? (
        <PaymentStatus
          key={paymentId}
          api={api}
          paymentId={paymentId}
          initialPayment={justSubmitted?.id === paymentId ? justSubmitted : null}
          onNewPayment={handleNewPayment}
        />
      ) : (
        <PaymentForm api={api} prefill={prefill} onSubmitted={handleSubmitted} />
      )}
    </main>
  );
}
