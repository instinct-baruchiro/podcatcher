-- Podcatcher usage, PostHog SQL editor. Israel time. Counts events, not people:
-- distinct_id is a random per-install id, no profiles. Debug builds carry internal_test=true.
SELECT toDate(toTimeZone(timestamp, 'Asia/Jerusalem')) AS day, event, count() AS events,
       uniq(distinct_id) AS installs_seen
FROM events
WHERE timestamp >= toDateTime('2026-11-20 00:00:00', 'Asia/Jerusalem')
  AND timestamp <  toDateTime('2026-12-20 00:00:00', 'Asia/Jerusalem')
  AND event LIKE 'podcatcher_%'
  AND properties.app = 'podcatcher'
  AND (properties.internal_test IS NULL OR properties.internal_test != true)
GROUP BY day, event ORDER BY day, event;
