package com.baruchiro.podcatcher.data

/** One podcast's contribution to the listening queue: its unplayed episodes, already in play order. */
data class QueueSource(val podcastId: String, val priority: Int, val unplayed: List<String>)

data class QueueItem(val podcastId: String, val episodeId: String)

/**
 * QUEUE-1 rules, fixed and deterministic (owner, 2026-11-19/20):
 * a lower priority number always plays before a higher one (strict order), and
 * podcasts that share a priority take turns, one episode each, in library order.
 */
fun buildQueue(sources: List<QueueSource>): List<QueueItem> =
    sources.groupBy { it.priority }.toSortedMap().values.flatMap { level ->
        val out = mutableListOf<QueueItem>()
        var round = 0
        while (level.any { round < it.unplayed.size }) {
            level.forEach { s -> s.unplayed.getOrNull(round)?.let { out.add(QueueItem(s.podcastId, it)) } }
            round++
        }
        out
    }
