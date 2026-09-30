# Scalatra Banking API

A minimal Scalatra service exposing ping and deposit operations.

## Run

```bash
sbt run
curl http://localhost:8080/ping

curl -X POST http://localhost:8080/accounts/account-123/deposits \
  -H 'Content-Type: application/json' \
  -d '{"amount": 100.00}'

curl -X POST http://localhost:8080/accounts/account-123/withdrawals \
  -H 'Content-Type: application/json' \
  -d '{"amount": 25.00}'
```

`GET /ping` responds with HTTP 200 and `pong`. A deposit creates an in-memory
account on first use and returns its updated balance:

```json
{"accountId":"account-123","balance":100.00}
```

Deposit and withdrawal amounts must be positive and have no more than two decimal places.
Withdrawals return HTTP 404 for unknown accounts and HTTP 409 when funds are insufficient.
Data is reset whenever the server restarts. Set `PORT` to listen on a port other
than 8080.

## Test

```bash
sbt test
```
