package com.fx.csvtest.assertion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * Normalizes and validates timestamp fields produced by the AUT.
 *
 * <p>The domain payment's {@code ProcessingTimestamp} is generated at runtime;
 * it cannot be predicted in the CSV expected values. This component provides:
 *
 * <ul>
 *   <li>{@link #isValidIsoTimestamp(String)} – verifies the field is a valid
 *       ISO-8601 local date-time.</li>
 *   <li>{@link #isFresh(String)} – verifies the timestamp falls within the
 *       configured freshness window (default 5 minutes).</li>
 *   <li>{@link #normalize(String)} – strips sub-second precision so two
 *       timestamps can be compared to-the-second.</li>
 * </ul>
 *
 * <p>Tests should call {@link #assertFresh(String)} to verify the timestamp
 * rather than doing a literal string comparison.
 */
@Component
@Slf4j
public class TimestampNormalizer {

    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Value("${fx.component.test.timestamp-freshness-minutes:5}")
    private long freshnessMinutes;

    /**
     * Returns {@code true} if {@code raw} can be parsed as an ISO-8601
     * local date-time ({@code yyyy-MM-dd'T'HH:mm:ss} with optional sub-seconds).
     */
    public boolean isValidIsoTimestamp(String raw) {
        if (raw == null || raw.isBlank()) return false;
        try {
            // Accept both with and without sub-seconds
            LocalDateTime.parse(normalize(raw), ISO_FORMATTER);
            return true;
        } catch (DateTimeParseException e) {
            log.debug("Timestamp '{}' is not a valid ISO-8601 local date-time: {}", raw, e.getMessage());
            return false;
        }
    }

    /**
     * Returns {@code true} if the timestamp is within {@link #freshnessMinutes}
     * of the current time (i.e., it was generated recently by the AUT).
     *
     * @throws IllegalArgumentException if {@code raw} is not a valid timestamp
     */
    public boolean isFresh(String raw) {
        LocalDateTime ts = LocalDateTime.parse(normalize(raw), ISO_FORMATTER);
        long minutesDiff = Math.abs(ChronoUnit.MINUTES.between(ts, LocalDateTime.now()));
        boolean fresh = minutesDiff <= freshnessMinutes;
        if (!fresh) {
            log.warn("Timestamp '{}' is {} minutes old – exceeds freshness window of {}m",
                    raw, minutesDiff, freshnessMinutes);
        }
        return fresh;
    }

    /**
     * Returns a human-readable assertion failure message if the timestamp is
     * invalid or stale, or {@code null} if it is valid and fresh.
     */
    public String assertFresh(String raw) {
        if (raw == null || raw.isBlank())
            return "ProcessingTimestamp is blank or null";
        if (!isValidIsoTimestamp(raw))
            return "ProcessingTimestamp '" + raw + "' is not a valid ISO-8601 date-time";
        if (!isFresh(raw))
            return "ProcessingTimestamp '" + raw + "' is older than " + freshnessMinutes + " minutes";
        return null;   // all good
    }

    /**
     * Strips sub-second precision (nanoseconds / milliseconds) to normalise
     * comparison, e.g. {@code "2024-04-15T10:30:00.123"} → {@code "2024-04-15T10:30:00"}.
     */
    public String normalize(String raw) {
        if (raw == null) return null;
        // Remove sub-second part if present
        return raw.contains(".") ? raw.substring(0, raw.indexOf('.')) : raw;
    }
}
