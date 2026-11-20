# REL-2 proposal: inference-only relay (NOT approved, nothing deployed)

Goal: the app talks to Gemini without the owner typing a key and without the key being in the APK.
Conversation storage is a separate, optional, disabled feature (REL-1) and is not part of this.

## Shape
- A Cloudflare Worker with one route, POST /chat. It holds the Gemini key as a Worker secret and calls a fixed model.
- The app sends the user turns and tool spec, gets the model reply. No key field in the app.

## Admission (personal trial, not public)
- Public "first call gets a token" is not admission control, so it is not used.
- The owner mints a few invite codes (revocable). An app install exchanges one code for its own install token; the code is single-use per install.
- Install tokens are random, stored hashed. Revoking a code or token stops that install. The token is extractable from a rooted device, so the real limit is the quota below, not secrecy.

## Atomic quota
- One Durable Object (SQLite backend, available on Workers Free, https://developers.cloudflare.com/durable-objects/platform/limits/) counts requests per install per day and globally. A Durable Object processes one request at a time, so the counters are exact. KV is not used for counters (eventually consistent).
- Caps (proposal, tune later): per install 100 requests/day, global 300/day, request body 16 KB, output tokens capped, fixed model.
- Fail closed: if the Durable Object is unreachable, the quota is exhausted, or the token is unknown, the Worker returns 429/401 and never calls Gemini. The app shows a plain message.
- Kill switch: delete the Worker secret.

## Limits and costs (verified from docs, 2026-11-20)
- Workers Free: 100,000 requests/day, 10 ms CPU per request, waiting on fetch does not count (https://developers.cloudflare.com/workers/platform/limits/).
- Gemini: a free tier exists; free-tier content may be used to improve Google products (https://ai.google.dev/gemini-api/docs/pricing). The owner's key lists gemini-3.1-flash-lite (the app's default model) as available for generateContent.
- NOT verified: whether the key's Google project has billing enabled (no console access, API does not say), Gemini free-tier request limits for that model, Durable Object free-plan daily request limits. No hard cost cap is promised until these are checked.

## Needs the owner
Approval to use a free-plan Worker on his Cloudflare account, a scoped Cloudflare token through a vault link, and placing the Gemini key as a Worker secret from the vault. Until then nothing is built or deployed.
