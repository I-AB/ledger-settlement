# Research notes: The Ledger Settlement Service

## Testcontainers service connections

Page title: Testcontainers :: Spring Boot

Date read: 2026-09-19

> For example, a PostgreSQLContainer will create both JdbcConnectionDetails and R2dbcConnectionDetails.

In PaymentControllerIT, @ServiceConnection on the PostgreSQLContainer makes Spring Boot build JdbcConnectionDetails from the container, and those connection details override the datasource url, username and password in application.yml, so the test talks to the throwaway container and not to my local database on port 5433.

## Agent Review

The agent was asked for a GET /payments/{id} endpoint and was given a written description of the service, with no code from this project. Its unedited output is saved as agent-get-payment.txt, and the line numbers below refer to that file.

| Defect | Found | Line | Fix in this project |
|---|---|---|---|
| Field injection with @Autowired instead of a constructor parameter | No (could not be judged) | 3 | The snippet uses a paymentRepository field that it never declares, so its injection style is not visible, and it calls the repository directly from the controller. In this project PaymentController receives SettlementService through its constructor and holds no repository. |
| JPA entity returned as the API type | Yes | 2, 4 | The agent returns ResponseEntity<PaymentEntity>. The endpoint now returns the PaymentResponse record (id, merchantId, amountMinor, currency), built by a toResponse helper, so recordedAt and the entity shape stay internal. |
| Missing @Valid or missing constraint annotations | Yes | 2 | The path variable has no constraint. It is now declared @PathVariable @NotBlank String id, and a HandlerMethodValidationException handler turns a blank id into a 400 problem detail. There is no request body, so @Valid does not apply. |
| Error body that is not a problem detail | Yes | 5 to 8 | The agent throws ResponseStatusException with HttpStatus.NOT_FOUND. The service now throws PaymentNotFoundException, and ProblemHandler turns it into a 404 application/problem+json body with type, title, status, detail and instance. |

Result of the fix on 2026-09-19: GET of an existing payment returned 200 with exactly id, merchantId, amountMinor and currency; GET of PAY-does-not-exist returned 404 application/problem+json with the detail "Payment not found: PAY-does-not-exist"; GET of a blank id returned 400 application/problem+json with the detail "id must not be blank".