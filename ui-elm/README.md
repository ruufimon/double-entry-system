# Ledger Bank customer UI (Elm)

An Elm 0.19.1 port of the Angular customer app in [`../ui`](../ui), built with
Vite and `vite-plugin-elm`. It has the same pages, the same API contract (`/api/*`
proxied to the Scalatra backend), and the same stylesheet, so the Angular app's
Playwright specs run against it unchanged.

It is a `Browser.application`. `Main.elm` owns routing and delegates to one
module per page under `src/Page/`. Each page has its own `Model`, `Msg`, `update`,
and `view`. The payment status page polls every 750 ms by scheduling the next
request after each response. `Main` drops responses and timers for pages that
are no longer shown.

## Develop

Start the backend from the repository root (`sbt run`), then:

```bash
bun install        # also installs the Elm compiler into node_modules
bun run start      # http://localhost:4500, proxies /api to http://localhost:8080
```

The dev server compiles with `--debug`, so Elm's time-travel debugger appears in
the bottom-right corner. Production builds use `--optimize`.

## Test and build

```bash
bun run test       # elm-test: formatting, validation, and route parsing
bun run build      # production build into dist/
```

Run the shared end-to-end suite from `../ui`:

```bash
cd ../ui
E2E_APP=ui-elm bun run e2e
```

## Layout

```text
src/Main.elm           application shell, routing, page dispatch
src/Route.elm          URL parsing and links
src/Api.elm            HTTP requests, JSON decoders, API errors
src/Format.elm         currency, date, and validation helpers
src/View.elm           shared view helpers
src/Page/*.elm         one module per page
src/styles.css         copy of ../ui/src/styles.scss
tests/FormatTest.elm   elm-test suite
```
