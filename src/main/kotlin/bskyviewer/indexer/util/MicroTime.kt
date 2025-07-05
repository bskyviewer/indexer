package bskyviewer.indexer.util

import java.time.Instant
import kotlin.time.Duration.Companion.microseconds

private const val MAX_SECOND = Long.MAX_VALUE / 1000000

fun Instant.toMicros(): Long {
    if (epochSecond + 1 > MAX_SECOND) return Long.MAX_VALUE
    val second = epochSecond * 1000000
    val microsecond = nano / 1000
    return second + microsecond
}

fun kotlinx.datetime.Instant.toMicros(): Long {
   if (epochSeconds + 1 > MAX_SECOND) return Long.MAX_VALUE
   val second = epochSeconds * 1000000
   val microsecond = nanosecondsOfSecond / 1000
   return second + microsecond
}

fun Long.micros(): kotlinx.datetime.Instant = kotlinx.datetime.Instant.Companion.fromEpochSeconds(0).plus(microseconds)
