import test from 'node:test';
import assert from 'node:assert/strict';
import {
  canCancel, describeCancelError, describeSubmitError, formatAmount, newToken, reasonText, validateAmount, validateToken, viewFor,
} from '../src/status.mjs';

test('badge text and messages follow the spec table', () => {
  assert.deepEqual(['PDNG', 'ACCP', 'ACSC', 'RJCT', 'CANC'].map((s) => viewFor(s).label),
    ['Processing', 'Authorised', 'Settled', 'Declined', 'Cancelled']);
  assert.equal(viewFor('PDNG').message, 'Waiting for your bank');
  assert.equal(viewFor('ACCP').message, 'Approved, settles overnight');
  assert.equal(viewFor('ACSC').message, 'Payment complete');
  assert.equal(viewFor('CANC').message, 'This payment was cancelled');
});

test('decline reasons, with an unknown reason shown as the generic text', () => {
  assert.equal(reasonText('AM04'), 'Insufficient funds');
  assert.equal(reasonText('AC04'), 'Card is closed or blocked');
  assert.equal(reasonText('FF01'), 'Card details are invalid');
  assert.equal(reasonText('NARR'), 'Payment could not be completed');
  assert.equal(reasonText('XXXX'), 'Payment could not be completed');
  assert.equal(reasonText(null), 'Payment could not be completed');
});

test('only PDNG and ACCP can be cancelled', () => {
  assert.deepEqual(['PDNG', 'ACCP', 'ACSC', 'RJCT', 'CANC'].map((status) => canCancel({ status })),
    [true, true, false, false, false]);
});

test('amount validation matches what the API accepts', () => {
  for (const ok of ['0.01', '125.50', '10000.00']) assert.equal(validateAmount(ok), null, ok);
  for (const bad of ['', '1', '1.5', '1.505', '-1.00', '0.00', 'abc', '1,50', ' 1.00']) assert.notEqual(validateAmount(bad), null, bad);
});

test('token validation and generation', () => {
  const token = newToken();
  assert.match(token, /^tok_[0-9a-f]{12}$/);
  assert.notEqual(newToken(), token);
  assert.equal(validateToken(token), null);
  assert.notEqual(validateToken(''), null);
  assert.notEqual(validateToken('4111111111111111'), null);
});

test('submit errors: 502 and network errors allow the same request again', () => {
  assert.equal(describeSubmitError({ status: 502, code: 'PROVIDER_UNAVAILABLE' }).retrySameRequest, true);
  assert.equal(describeSubmitError({ status: 0, code: 'NETWORK_ERROR' }).retrySameRequest, true);
  assert.equal(describeSubmitError({ status: 502, code: 'PROVIDER_UNAVAILABLE' }).message,
    "We couldn't submit your payment. Please try again.");
});

test('submit errors: a rejected token asks for a new one, and no raw errors leak', () => {
  const rejected = describeSubmitError({ status: 422, code: 'PROVIDER_REJECTED', detail: 'SECRET PROVIDER TEXT' });
  assert.equal(rejected.newToken, true);
  assert.ok(!rejected.message.includes('SECRET'));
  assert.ok(describeSubmitError({ status: 422, code: 'INVALID_AMOUNT' }).message.length > 0);
  assert.ok(describeSubmitError({ status: 500, code: 'INTERNAL_ERROR' }).message.length > 0);
});

test('cancel errors', () => {
  assert.equal(describeCancelError({ code: 'PAYMENT_NOT_CANCELLABLE' }), 'This payment can no longer be cancelled');
  assert.equal(describeCancelError({ status: 502 }), "We couldn't cancel the payment. Please try again.");
});

test('amounts are shown with their currency and nothing else', () => {
  assert.equal(formatAmount({ value: '125.50', currency: 'GBP' }), '125.50 GBP');
});
