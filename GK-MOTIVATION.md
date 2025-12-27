# Motivation: JSON Protocol for Chrome Sandbox Compatibility

## Use Case

I'm building a Chrome extension that embeds a ClojureScript REPL using SCI. The extension needs to communicate with the browser-server over WebSocket to evaluate code in the browser context.

Chrome extensions have a powerful security feature: [sandboxed pages](https://developer.chrome.com/docs/extensions/mv3/sandboxingEval/). Sandboxed pages can run untrusted code safely because they're isolated from the extension's privileged APIs. This is the recommended approach for extensions that need to evaluate dynamic code.

## Problem Encountered

Chrome's sandboxed pages have a critical restriction: **`eval()` and `new Function()` are disabled** by Content Security Policy.

The current browser-server uses EDN for WebSocket messages:

```clojure
;; Server sends:
(websocket-send! (str {:op :eval :code "(+ 1 2)" :id "123"}))

;; Browser receives EDN string, must parse it:
(cljs.reader/read-string message)  ;; ❌ Uses eval internally!
```

`cljs.reader/read-string` relies on `eval()` for certain constructs, which fails in the sandbox:

```
Refused to evaluate a string as JavaScript because 'unsafe-eval'
is not an allowed source of script in the following Content Security Policy...
```

This completely blocks the use of browser-server in Chrome extensions using the recommended sandbox architecture.

## Alternatives Considered

1. **Disable the sandbox** - Would work but sacrifices security; Chrome explicitly warns against this for code evaluation use cases

2. **Use a pure-ClojureScript EDN parser** - No mature option exists that avoids `eval()` entirely; would need to write/maintain one

3. **Proxy through a non-sandboxed page** - Adds complexity and potential security issues; defeats the sandbox purpose

4. **Use transit-cljs** - Another Clojure serialization format, but also has `eval()` dependencies in the reader

5. **Use JSON** - Native `JSON.parse()` works in sandboxed pages with zero restrictions; JSON is universally supported

## Solution

### Switch from EDN to JSON

Replace `clojure.edn` with `cheshire.core` for JSON serialization:

```clojure
;; Before (EDN):
(:require [clojure.edn :as edn])
(edn/read-string message)
(websocket-send! (str msg))

;; After (JSON):
(:require [cheshire.core :as json])
(json/parse-string message true)  ;; keywordize keys
(websocket-send! (json/generate-string msg))
```

### Convert `:op` to `:type` for Chrome protocol

The Chrome sandbox messaging protocol uses `:type` instead of `:op`:

```clojure
(let [msg (if (:op msg)
            (-> msg (assoc :type (name (:op msg))) (dissoc :op))
            msg)]
  (httpkit/send! chan (json/generate-string msg)))
```

### Handle response format differences

Chrome sandbox returns simpler response structures. Added parsing to convert back to nREPL format:

```clojure
(let [{:keys [id type result error]} (json/parse-string message true)]
  (cond
    (= type "result") {"value" result "status" ["done"]}
    (= type "error")  {"err" error "status" ["done"]}
    ...))
```

### Route Babashka-prefixed IDs

Added support for `bb-*` prefixed message IDs that route to a Babashka handler instead of the nREPL client, enabling hybrid browser+Babashka evaluation:

```clojure
(if (and id (str/starts-with? (str id) "bb-"))
  (when-let [handler @babashka-response-handler]
    (handler id result error))
  ;; ... normal nREPL routing
```

## Incompatibilities / Tradeoffs

### Breaking Change: Wire Protocol

**This changes the WebSocket wire format from EDN to JSON.**

- ❌ **Existing browser code expecting EDN will break**
- Browser-side code must be updated to use `JSON.parse()` instead of `cljs.reader/read-string`
- Browser-side code must send JSON instead of EDN strings

### New Dependency

- Adds `cheshire` dependency for JSON encoding/decoding
- Cheshire is widely used and well-maintained, minimal risk

### Message Key Changes

- `:op` → `:type` in outgoing messages
- Responses use `result`/`error` instead of `value`/`err`

### Possible Mitigation

A future enhancement could make the protocol configurable:

```clojure
(def protocol :json)  ;; or :edn

(defn serialize [msg]
  (case protocol
    :json (json/generate-string msg)
    :edn  (pr-str msg)))
```

This would maintain backwards compatibility while supporting both use cases.

## How to Test

1. Start browser-server with a scittle application

2. Connect via WebSocket and send JSON:
   ```javascript
   const ws = new WebSocket('ws://localhost:1340');
   ws.onmessage = (e) => console.log(JSON.parse(e.data));
   ws.send(JSON.stringify({type: 'eval', code: '(+ 1 2)', id: '1'}));
   ```

3. Verify response comes back as JSON:
   ```javascript
   {id: "1", type: "result", result: "3"}
   ```

4. For Chrome extension testing:
   - Create a sandboxed page with `"sandbox": "allow-scripts"` in manifest
   - Verify WebSocket communication works without CSP errors
   - Evaluate code and confirm results return correctly
