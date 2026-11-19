# Stories

> This file is the source of truth for product behavior. Changing what a story
> says the product does — adding, editing, deleting behavior — requires
> explicit approval from the repository owner. Wording fixes that don't change
> behavior don't need it. Code and tests adapt to the stories, not the other
> way around. See `CLAUDE.md` for the test-coverage rule.
>
> These stories persist the owner's use cases (from his own voice/text
> messages, 2026-11-19) so nobody has to re-ask him. Each story carries an
> explicit status: **tested** (automated tagged test passes), **implemented**
> (works in code, automated test pending), **documented** (agreed behavior,
> not built yet). The `<!-- @unimplemented -->` marker exempts a story from
> the coverage gate until its tagged test lands.

## FLOW-1 — Onboarding order: subscribe first, then listening settings

As a listener, when I add a podcast the app first subscribes me, and only
then asks about my listening/download preferences (offering my defaults and
per-podcast choices), so that setup happens in one orderly pass.

Status: documented. The app subscribes and has per-podcast settings, but
there is no guided ask and no defaults concept yet.

Acceptance:
- Subscribing completes before any settings question is asked.
- After subscribing, the listener is offered: use default settings, or set
  this podcast's own settings.

<!-- @unimplemented -->

## SUB-1 — Subscribe by RSS/feed link

As a listener, I want to paste a podcast feed link so that the podcast is
added to my library. A link that turns out to be a plain webpage (not a feed)
is rejected with a clear message and falls back to search instead of adding
garbage.

Status: tested (JVM tests on fixtures captured from live services) and
covered in the on-device flow.

## SUB-2 — Subscribe by name search

As a listener, I want to type a podcast name (Hebrew or English) so that I
can pick the right podcast from matching results (title, author, artwork)
without knowing its feed URL.

Status: tested (JVM tests) for result parsing; the on-device pick-dialog flow
is implemented, screenshot coverage pending.

## SUB-3 — Import subscriptions from another app's export file

As a listener, I want to import a subscriptions file (OPML) I exported from
another podcast app, so that I don't have to re-add every podcast by hand.

Status: documented (owner requirement, 2026-11-19). Not built.

Acceptance:
- An OPML file import adds every valid feed to the library.
- Feeds that fail to load are reported, not silently dropped.
- Duplicates of existing subscriptions are skipped.

<!-- @unimplemented -->

## LIB-1 — Library lists and removes podcasts

As a listener, I want to see my subscribed podcasts with artwork and titles,
open one to its episodes, and remove one (with a confirmation) so that my
library stays mine to manage.

Status: implemented; covered in the on-device flow.

## PLAY-1 — Play an episode from a play button

As a listener, I want a play button on every episode so that listening starts
immediately — playback is a button, not a conversation.

Status: implemented; covered in the on-device flow (asserts real
STATE_PLAYING via media session).

## PLAY-2 — Playback continues in the background with system controls

As a listener, I want playback to keep going when I leave the app and to be
controllable from the media notification / lockscreen, so that I can listen
while doing other things.

Status: implemented; covered in the on-device flow (media notification).

## PLAY-3 — Playback speed and intro skip follow the podcast's policy

As a listener, I want per-podcast playback speed (×1–×2) and skip-intro
(0/15/30/60s) so that each show plays the way I like it.

Status: implemented; automated coverage pending.

<!-- @unimplemented -->

## DL-1 — Download an episode to the device

As a listener, I want a download button on every episode so that I can keep
it on my device; the row shows it as on-device only once the file is really
there.

Status: implemented; covered in the on-device flow (waits for real
on-device state via the download-complete receiver).

## DL-2 — Play a downloaded episode from local storage

As a listener, I want playback of a downloaded episode to use the local file
so that it works offline.

Status: implemented; covered in the on-device flow (plays after download).

## DL-3 — Keep-N retention prunes old downloads

As a listener, I want each podcast to keep only the newest N downloaded
episodes (1/3/5/10, my choice) so that storage doesn't fill up; stale files
and their records are actually deleted.

Status: implemented; automated coverage pending.

<!-- @unimplemented -->

## DL-4 — New episodes auto-download when the podcast's policy says so

As a listener, I want new episodes of podcasts I marked to download
automatically in the background (roughly hourly, battery-friendly), with a
summary notification, so that fresh episodes wait for me on the device.

Status: implemented; automated coverage pending.

<!-- @unimplemented -->

## DL-5 — Archive mode and download order for the back-catalog

As a listener, I want control over downloading a podcast's archive: whether
older unplayed episodes also download (not just new ones), and whether they
download oldest-first or newest-first, so that I can work through a
back-catalog my way without filling my storage.

Modeled on Podcast Addict's Archive Mode (podcastaddict.com/faq/660):
a rolling set of unplayed episodes stays downloaded; when one is removed,
another eligible unplayed episode refills the slot; a keep-at-most limit
protects storage (unlimited is treated as a small limit); "older first" can
be set globally or per podcast.

Status: documented (owner requirement, 2026-11-19: "להוריד קודם את הפרקים
הראשונים, קודם את הפרקים האחרונים, ארכיון... לבדוק איך זה עובד בפודקאסט
אדיקט"). Not built.

Acceptance:
- Per podcast (and by default): archive downloads on/off.
- Order setting: oldest-first or newest-first, global and per podcast.
- A keep-at-most limit always applies to archive downloads.

<!-- @unimplemented -->

## SET-1 — Default settings with per-podcast overrides

As a listener, I want app-wide default listening/download settings that new
podcasts inherit, which I can override per podcast, so that I set my taste
once and only handle exceptions.

Status: documented (owner requirement, 2026-11-19: "הגדרות דיפולטיביות
והגדרות לכל פודקאסט"). Not built; today only per-podcast settings exist.

<!-- @unimplemented -->

## POL-1 — Per-podcast policy is editable

As a listener, I want to set auto-download, keep-N, speed and skip per podcast
so that each show behaves the way I want.

Status: implemented; automated coverage pending.

<!-- @unimplemented -->

## CONV-1 — Conversational control of app behavior

As a listener, I want to tell the app in natural language what I want — using
podcast-domain tools and knowledge — so that it changes the app's behavior
and settings for me. The conversation drives the app's own settings and
actions; it does not discuss episode content.

Status: partially implemented (2026-11-19). Built: conversation screen, the
podcast-domain tool layer (list/search/subscribe/get_policy/set_policy incl.
global defaults) and the deterministic executor — JVM-tested via a TESTS-ONLY
stub backend that proves the model-reply→settings wire. Not built: a real
model backend (owner's choice: his Hermes endpoint, on-device model, or a
paid API — none authorized/connected yet), so the shipped screen answers
honestly that no engine is attached. Playback intentionally stays
button-driven (owner direction).

<!-- @unimplemented -->
