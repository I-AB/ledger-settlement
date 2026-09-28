package com.ledger;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class ProblemHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        String detail = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", detail, request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleParameterValidation(
            HandlerMethodValidationException exception, HttpServletRequest request) {
        String detail = exception.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> result.getMethodParameter().getParameterName()
                                + " " + error.getDefaultMessage()))
                .sorted()
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", detail, request);
    }

    @ExceptionHandler(MerchantNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleMerchantNotFound(
            MerchantNotFoundException exception, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "merchant-not-found", "Merchant not found",
                exception.getMessage(), request);
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ProblemDetail> handlePaymentNotFound(
            PaymentNotFoundException exception, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "payment-not-found", "Payment not found",
                exception.getMessage(), request);
    }

    private ResponseEntity<ProblemDetail> problem(
            HttpStatus status, String typeName, String title, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:ledger:problem:" + typeName));
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}