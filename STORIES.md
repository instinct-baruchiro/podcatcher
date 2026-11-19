# Stories

> This file is the source of truth for product behavior. Changing what a story
> says the product does — adding, editing, deleting behavior — requires
> explicit approval from the repository owner. Wording fixes that don't change
> behavior don't need it. Code and tests adapt to the stories, not the other
> way around. See `CLAUDE.md` for the test-coverage rule.
>
> Project note: the `<!-- @unimplemented -->` marker is used here for behavior
> that IS implemented in the app but has no automated test yet; those sections
> say so explicitly. The gate exempts them until a tagged test lands.

## SUB-1 — Subscribe by RSS/feed link

As a listener, I want to paste a podcast feed link so that the podcast is
added to my library. A link that turns out to be a plain webpage (not a feed)
is rejected with a clear message and falls back to search instead of adding
garbage.

## SUB-2 — Subscribe by name search

As a listener, I want to type a podcast name (Hebrew or English) so that I can
pick the right podcast from matching results (title, author, artwork) without
knowing its feed URL.

## LIB-1 — Library lists and removes podcasts

As a listener, I want to see my subscribed podcasts with artwork and titles,
open one to its episodes, and remove one (with a confirmation) so that my
library stays mine to manage.

## PLAY-1 — Play an episode from a play button

As a listener, I want a play button on every episode so that listening starts
immediately — playback is a button, not a conversation.

## PLAY-2 — Playback continues in the background with system controls

As a listener, I want playback to keep going when I leave the app and to be
controllable from the media notification / lockscreen, so that I can listen
while doing other things.

## PLAY-3 — Playback speed and intro skip follow the podcast's policy

As a listener, I want per-podcast playback speed (×1–×2) and skip-intro
(0/15/30/60s) so that each show plays the way I like it.

<!-- @unimplemented -->
Status: implemented in the app; automated coverage pending.

## DL-1 — Download an episode to the device

As a listener, I want a download button on every episode so that I can keep it
on my device; the row shows it as on-device only once the file is really
there.

## DL-2 — Play a downloaded episode from local storage

As a listener, I want playback of a downloaded episode to use the local file
so that it works offline.

## DL-3 — Keep-N retention prunes old downloads

As a listener, I want each podcast to keep only the newest N downloaded
episodes (1/3/5/10, my choice) so that storage doesn't fill up; stale files
and their records are actually deleted.

<!-- @unimplemented -->
Status: implemented in the app; automated coverage pending.

## DL-4 — New episodes auto-download when the podcast's policy says so

As a listener, I want new episodes of podcasts I marked to download
automatically in the background (roughly hourly, battery-friendly), with a
summary notification, so that fresh episodes wait for me on the device.

<!-- @unimplemented -->
Status: implemented in the app; automated coverage pending.

## POL-1 — Per-podcast policy is editable

As a listener, I want to set auto-download, keep-N, speed and skip per podcast
so that each show behaves the way I want.

<!-- @unimplemented -->
Status: implemented in the app; automated coverage pending.
