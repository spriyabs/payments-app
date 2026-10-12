package com.example.payments.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every error into an RFC 7807 problem response (application/problem+json) with a
 * stable {@code code}. Never leaks stack traces, SQL or request bodies.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> handleApi(ApiException e) {
        return problem(e.code(), e.getMessage(), e.payment());
    }

    @ExceptionHandler(InvalidAmountException.class)
    ResponseEntity<Object> handleInvalidAmount(InvalidAmountException e) {
        return problem(ErrorCode.INVALID_AMOUNT, e.getMessage(), null);
    }

    @ExceptionHandler(UnsupportedCurrencyException.class)
    ResponseEntity<Object> handleUnsupportedCurrency(UnsupportedCurrencyException e) {
        return problem(ErrorCode.CURRENCY_NOT_SUPPORTED, e.getMessage(), null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return problem(ErrorCode.INTERNAL_ERROR, "Unexpected error", null);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        FieldError first = e.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        String detail = first == null ? "Request failed validation" : first.getField() + " " + first.getDefaultMessage();
        return problem(ErrorCode.VALIDATION_ERROR, detail, null);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return problem(ErrorCode.VALIDATION_ERROR, "Request body is missing or is not valid JSON", null);
    }

    @Override
    protected ResponseEntity<Object> handleServletRequestBindingException(
            ServletRequestBindingException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return problem(ErrorCode.VALIDATION_ERROR, e.getBody().getDetail(), null);
    }

    /** A path id that is not a UUID can never match a payment. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return problem(ErrorCode.PAYMENT_NOT_FOUND, "No payment with that id", null);
    }

    private ResponseEntity<Object> problem(ErrorCode code, String detail, Object payment) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(code.status(), detail);
        body.setTitle(code.status().getReasonPhrase());
        body.setProperty("code", code.name());
        if (payment != null) {
            body.setProperty("payment", payment);
        }
        return ResponseEntity.status(code.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }
}
