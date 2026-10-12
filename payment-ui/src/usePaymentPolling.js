import { useCallback, useEffect, useRef, useState } from 'react';
import { createPoller } from './poller.mjs';

/**
 * React wrapper around createPoller. Starts when paymentId changes, stops on unmount.
 * problem is null, 'notFound' or 'stalled'. failures counts consecutive failed polls.
 * initialPayment (for example the submit response) is shown while the first poll is in flight.
 */
export function usePaymentPolling(api, paymentId, initialPayment = null) {
  const [payment, setPayment] = useState(initialPayment);
  const [problem, setProblem] = useState(null);
  const [failures, setFailures] = useState(0);
  const pollerRef = useRef(null);

  useEffect(() => {
    setPayment(initialPayment);
    setProblem(null);
    setFailures(0);

    const poller = createPoller({
      api,
      paymentId,
      onPayment: setPayment,
      onProblem: setProblem,
      onFailures: setFailures,
      env: { isHidden: () => document.hidden },
    });
    pollerRef.current = poller;
    const onVisibility = () => poller.visibilityChanged();
    document.addEventListener('visibilitychange', onVisibility);
    poller.start();

    return () => {
      document.removeEventListener('visibilitychange', onVisibility);
      poller.stop();
      pollerRef.current = null;
    };
    // initialPayment is only a placeholder for the first render of a payment.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [api, paymentId]);

  const refresh = useCallback(() => pollerRef.current?.refresh(), []);
  const applyPayment = useCallback((next) => pollerRef.current?.apply(next), []);

  return { payment, problem, failures, refresh, applyPayment };
}
