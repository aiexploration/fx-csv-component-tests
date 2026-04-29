package com.fx.csvtest.model;

import lombok.Builder;
import lombok.Data;

/**
 * Represents one row in a CSV test-case file.
 *
 * <p>Fields are split into three logical groups:
 * <ol>
 *   <li><b>Test metadata</b> – id, description, category, expected outcome</li>
 *   <li><b>pacs.009 input</b> – all fields needed to build the ISO XML message</li>
 *   <li><b>Expected assertions</b> – values to check in the domain payment output</li>
 * </ol>
 *
 * <p>Blank / null string values in optional fields are treated as "field absent".
 * The {@code expectedOutcome} column drives the routing assertion:
 * {@code VALID} expects the message to appear on {@code fx.payment.valid} with
 * matching domain fields; {@code INVALID} expects it on {@code fx.payment.invalid}.
 */
@Data
@Builder
public class TestCase {

    // ── Test metadata ──────────────────────────────────────────────────────
    /** Unique test identifier (e.g. TC-001). Must be unique within the suite. */
    private String testId;
    /** Short description displayed in the report. */
    private String description;
    /** Logical group (e.g. HappyPath, BoundaryAmount, BoundaryBIC). */
    private String category;
    /** VALID or INVALID – drives routing and assertion strategy. */
    private ExpectedOutcome expectedOutcome;
    /**
     * For INVALID tests: substring expected in the validation error stored in DB.
     * Leave blank if no specific error text check is needed.
     */
    private String expectedErrorContains;

    // ── pacs.009 Group Header ──────────────────────────────────────────────
    private String msgId;
    private String creDtTm;            // ISO 8601 – leave blank to auto-generate
    private String nbOfTxs;            // defaults to "1"
    private String settlementMethod;   // CLRG | COVE | GROS | INDA

    // ── pacs.009 Payment Identification ───────────────────────────────────
    private String instrId;            // optional
    private String endToEndId;
    /** Transaction ID – used as the correlation key between input and output. */
    private String txId;
    private String uetr;               // optional UUID v4

    // ── pacs.009 Settlement ────────────────────────────────────────────────
    private String amount;
    private String currency;           // ISO 4217 3-letter code
    private String settlementDate;     // YYYY-MM-DD
    private String exchangeRate;       // optional decimal
    private String chargeBearer;       // CRED | DEBT | SHAR | SLEV

    // ── pacs.009 Debtor ────────────────────────────────────────────────────
    private String debtorBic;
    private String debtorName;         // optional
    private String debtorIban;         // optional
    private String debtorAgentBic;

    // ── pacs.009 Creditor ──────────────────────────────────────────────────
    private String creditorAgentBic;
    private String creditorBic;
    private String creditorName;       // optional
    private String creditorIban;       // optional

    // ── pacs.009 Optional fields ───────────────────────────────────────────
    private String purposeCode;        // CORT | TREA | BEXP | INTC etc.
    private String remittanceInfo;     // max 140 chars

    // ── XML override flags (to force invalid conditions) ───────────────────
    /** Comma-separated list of elements to omit, e.g. "TxId,EndToEndId". */
    private String omitElements;
    /**
     * Raw XML fragment to inject INSTEAD of the standard element (for injection
     * of deliberately malformed content), e.g. "IntrBkSttlmAmt=<IntrBkSttlmAmt Ccy='USDX'>-5</IntrBkSttlmAmt>".
     * Format: elementName=rawXmlSnippet.  Multiple overrides separated by {@code ||}.
     */
    private String rawXmlOverrides;

    // ── Expected domain payment assertions (VALID tests only) ─────────────
    private String expectedPaymentStatus;      // defaults to PROCESSED
    private String expectedSettlementAmount;
    private String expectedSettlementCurrency;
    private String expectedSettlementDate;
    private String expectedSettlementMethod;
    private String expectedExchangeRate;
    private String expectedDebtorBic;
    private String expectedCreditorBic;
    private String expectedDebtorIban;
    private String expectedCreditorIban;
    private String expectedDebtorName;
    private String expectedCreditorName;
    private String expectedDebtorAgentBic;
    private String expectedCreditorAgentBic;
    private String expectedChargeBearer;
    private String expectedPurposeCode;
    private String expectedRemittanceInfo;
    private String expectedUetr;
    private String expectedEndToEndId;

    // ── Helpers ────────────────────────────────────────────────────────────

    public boolean isValid() {
        return ExpectedOutcome.VALID == expectedOutcome;
    }

    public boolean shouldOmit(String element) {
        if (omitElements == null || omitElements.isBlank()) return false;
        for (String e : omitElements.split(",")) {
            if (e.trim().equalsIgnoreCase(element)) return true;
        }
        return false;
    }
}
