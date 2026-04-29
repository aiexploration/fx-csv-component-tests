package com.fx.csvtest.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Records the outcome of executing one {@link TestCase}.
 *
 * <p>Each assertion failure is captured as a separate string in
 * {@link #assertionFailures}, so the report shows exactly which fields
 * mismatched rather than just a pass/fail status.
 */
@Data
@Builder
public class TestResult {

    public enum Status { PASS, FAIL, ERROR, SKIPPED }

    // ── Identity ───────────────────────────────────────────────────────────
    private String testId;
    private String category;
    private String description;
    private ExpectedOutcome expectedOutcome;
    private String txId;

    // ── Execution ──────────────────────────────────────────────────────────
    private Status status;
    private long durationMs;
    private LocalDateTime executedAt;

    /** The generated pacs.009 XML that was sent (truncated in reports). */
    private String sentXml;

    /** The domain payment XML received from fx.payment.valid (if any). */
    private String receivedDomainXml;

    /** The raw text received from fx.payment.invalid (if any). */
    private String receivedInvalidXml;

    // ── Failures & errors ─────────────────────────────────────────────────
    @Builder.Default
    private List<String> assertionFailures = new ArrayList<>();

    /** Top-level error message if an unexpected exception occurred. */
    private String errorMessage;

    // ── DB correlation ────────────────────────────────────────────────────
    /** UUID assigned by the AUT on persistence (populated from DB query). */
    private String persistedPaymentId;

    /** DB status of the persisted record (PROCESSED / INVALID / ERROR). */
    private String persistedStatus;

    /** DB validation errors column (populated for INVALID tests). */
    private String persistedValidationErrors;

    // ── Helpers ───────────────────────────────────────────────────────────

    public boolean isPassed() { return status == Status.PASS; }
    public boolean hasFailed() { return status == Status.FAIL || status == Status.ERROR; }

    public void addFailure(String msg) {
        if (assertionFailures == null) assertionFailures = new ArrayList<>();
        assertionFailures.add(msg);
    }
}
