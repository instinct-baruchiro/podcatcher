# REL-1 design notes (proposal, not approved, nothing built)

Owner gates (2026-11-20 09:36 voice): usage analytics, and stored conversations
with a notice. See STORIES.md REL-1. No account, deployment, spend or real user
data until the owner approves this design.

## Conversation storage

Candidate: a small Cloudflare Worker with a D1 database on the owner's existing
Cloudflare account. Whether his plan has Workers or D1 enabled is unverified.

Identity and authorization
- A shared token inside the APK is extractable, so it proves nothing. Instead
  each install registers once and gets its own random secret from the Worker.
  The Worker stores only a hash. The app keeps the secret in SecretStore.
- Every write and every delete request carries that install secret. Possession
  of it is the only proof of ownership of the data, so the app shows the
  install id in settings with a "delete my conversations" action.
- The owner can revoke one install (or all) by deleting its hash. Revoked
  installs get a refusal and the app keeps working without storing.
- Registration is rate limited per day to bound abuse. Play Integrity is not
  available for a GitHub-distributed APK, so this is a bound, not a proof of a
  genuine app.

Consent
- Nothing is sent before the user sees the notice and agrees. Declining leaves
  the conversation working and stores nothing. The notice states what is kept,
  why (oversight and service improvement), who can read it, for how long, and
  how to delete.

Abuse, quota and failure
- Caps: message size, messages per install per day, global per-day writes below
  the free daily limits. Over a cap the Worker refuses; on the free plan the
  database refuses instead of billing.
- If storage is unreachable or refused, the chat keeps working (storage is for
  oversight, not safety); the app retries a small bounded local queue and then
  drops it. Owner may prefer fail-closed; that is a product choice.

Data and logs
- Stored: install id, timestamp, role, message text, app version, a channel flag
  ("test" or "prod"). Never the Gemini key, never episode audio.
- Worker request logging excludes bodies and the install secret header.
- Debug and CI builds are "test" and disabled for real collection; test rows
  are purged. Separate databases for test and prod may carry cost; unverified.

Retention is an owner-purpose decision, not a default. Tradeoff: a short window
(for example 30 days) serves investigating recent problems and is easier to
justify in the notice; longer keeps more history at more privacy cost. A flagged
conversation can be kept longer only if the notice says so.

## Usage analytics

PostHog Android SDK is supported. Events only: names and coarse settings, no
conversation text, session replay and error autocapture off, user opt-out in
settings. Reuse of the owner's existing project and token, and what that costs,
is unverified (free plan: one project, allowance shared across projects).

## Exact reads needed to remove the unknowns (read-only, no creation)
1. Cloudflare account: plan or subscription for Workers, and whether D1 is
   enabled / existing databases and Workers (account settings read, D1 read,
   Workers scripts read).
2. PostHog organization: current plan and this month's usage against the free
   allowance (organization or billing read; may exceed the read-only key's
   scope).
