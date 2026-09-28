# Endpoint log: Ledger settlement service

Five calls made against the running service on http://localhost:8080 on 2026-09-19. The Time column gives the clock time of the call and the response time.

| Request | Status | Body summary | Time |
|---|---|---|---|
| POST /payments with merchantId MR-4471, amountMinor 128450, currency GBP | 201 Created | Location header /payments/PAY-b139e8e6-7c18-406a-aa9d-644716deaa94; JSON body with that id, merchantId MR-4471, amountMinor 128450 and currency GBP | 19:15:03, 0.622 s |
| POST /payments with amountMinor -5 | 400 Bad Request | application/problem+json with type, title Validation failed, status 400, detail "amountMinor must be greater than 0" and instance /payments | 19:15:03, 0.052 s |
| POST /payments with a blank merchantId | 400 Bad Request | application/problem+json with title Validation failed, status 400, detail "merchantId must not be blank" and instance /payments | 19:15:03, 0.006 s |
| GET /payments/settlement?merchantId=MR-4471 | 200 OK | application/json body {"merchantId":"MR-4471","owedMinor":124469} | 19:15:04, 0.704 s |
| GET /payments/settlement?merchantId=MR-0000 (unknown merchant) | 404 Not Found | application/problem+json with title Merchant not found, status 404, detail "Merchant not found: MR-0000" and instance /payments/settlement | 19:15:04, 0.018 s |