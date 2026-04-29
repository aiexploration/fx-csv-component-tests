package com.fx.csvtest.assertion;

import com.fx.csvtest.model.TestCase;
import com.fx.csvtest.model.TestResult;
import com.fx.csvtest.model.DomainPayment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Validates a received {@link DomainPayment} object against the expected values
 * declared in the test case CSV row.
 *
 * <p>Only non-blank expected fields in the CSV are asserted; blank expected
 * columns are treated as "don't care". This allows sparse CSV rows where only
 * the interesting fields are checked.
 *
 * <p>All failures are accumulated in the {@link TestResult} rather than throwing
 * immediately, so a single test run reports all mismatches at once.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DomainPaymentAsserter {

    private final TimestampNormalizer timestampNormalizer;

    /**
     * Runs all expected-field assertions against the received domain payment.
     *
     * @param tc     test case with expected values (from CSV)
     * @param domain the parsed domain payment received from fx.payment.valid
     * @param result accumulates assertion failures
     */
    public void assertAll(TestCase tc, DomainPayment domain, TestResult result) {

        // ── Always-present assertions ─────────────────────────────────────

        // PaymentId must be a valid UUID (assigned by AUT)
        assertNotBlank(result, "PaymentId", domain.getPaymentId());
        if (domain.getPaymentId() != null) {
            assertUuid(result, "PaymentId", domain.getPaymentId());
        }

        // OriginalMessageId must be present
        assertNotBlank(result, "OriginalMessageId", domain.getOriginalMessageId());

        // TransactionId must match what was sent (correlation key)
        assertEqual(result, "TransactionId", tc.getTxId(), domain.getTransactionId());

        // PaymentStatus must be "PROCESSED" for valid messages
        String expectedStatus = blank(tc.getExpectedPaymentStatus()) ? "PROCESSED" : tc.getExpectedPaymentStatus();
        assertEqual(result, "PaymentStatus", expectedStatus, domain.getPaymentStatus());

        // ProcessingTimestamp must be valid ISO-8601 and fresh (< 5 min old)
        String tsFailure = timestampNormalizer.assertFresh(domain.getProcessingTimestamp());
        if (tsFailure != null) {
            result.addFailure("ProcessingTimestamp: " + tsFailure);
        }

        // ── Optional field assertions (only if expected value set in CSV) ──

        assertOptionalDecimal(result, "SettlementAmount",
                tc.getExpectedSettlementAmount(), domain.getSettlementAmount());

        assertOptional(result, "SettlementCurrency",
                tc.getExpectedSettlementCurrency(), domain.getSettlementCurrency());

        assertOptional(result, "SettlementDate",
                tc.getExpectedSettlementDate(), domain.getSettlementDate());

        assertOptional(result, "SettlementMethod",
                tc.getExpectedSettlementMethod(), domain.getSettlementMethod());

        assertOptionalDecimal(result, "ExchangeRate",
                tc.getExpectedExchangeRate(), domain.getExchangeRate());

        assertOptional(result, "DebtorBIC",
                tc.getExpectedDebtorBic(), domain.getDebtorBic());

        assertOptional(result, "CreditorBIC",
                tc.getExpectedCreditorBic(), domain.getCreditorBic());

        assertOptional(result, "DebtorIBAN",
                tc.getExpectedDebtorIban(), domain.getDebtorIban());

        assertOptional(result, "CreditorIBAN",
                tc.getExpectedCreditorIban(), domain.getCreditorIban());

        assertOptional(result, "DebtorName",
                tc.getExpectedDebtorName(), domain.getDebtorName());

        assertOptional(result, "CreditorName",
                tc.getExpectedCreditorName(), domain.getCreditorName());

        assertOptional(result, "DebtorAgentBIC",
                tc.getExpectedDebtorAgentBic(), domain.getDebtorAgentBic());

        assertOptional(result, "CreditorAgentBIC",
                tc.getExpectedCreditorAgentBic(), domain.getCreditorAgentBic());

        assertOptional(result, "ChargeBearer",
                tc.getExpectedChargeBearer(), domain.getChargeBearer());

        assertOptional(result, "PurposeCode",
                tc.getExpectedPurposeCode(), domain.getPurposeCode());

        assertOptional(result, "RemittanceInfo",
                tc.getExpectedRemittanceInfo(), domain.getRemittanceInfo());

        assertOptional(result, "UETR",
                tc.getExpectedUetr(), domain.getUetr());

        assertOptional(result, "EndToEndId",
                tc.getExpectedEndToEndId(), domain.getEndToEndId());

        // Log summary
        if (result.getAssertionFailures().isEmpty()) {
            log.debug("[{}] All domain payment assertions passed.", tc.getTestId());
        } else {
            log.warn("[{}] {} domain payment assertion(s) failed.",
                    tc.getTestId(), result.getAssertionFailures().size());
        }
    }

    // ── Assertion helpers ──────────────────────────────────────────────────

    private void assertNotBlank(TestResult result, String field, String actual) {
        if (actual == null || actual.isBlank()) {
            result.addFailure(field + ": expected non-blank but was <blank/null>");
        }
    }

    private void assertEqual(TestResult result, String field, String expected, String actual) {
        if (!Objects.equals(expected, actual)) {
            result.addFailure(field + ": expected='" + expected + "' actual='" + actual + "'");
        }
    }

    private void assertOptional(TestResult result, String field, String expected, String actual) {
        if (blank(expected)) return;   // not specified in CSV → skip
        if (!Objects.equals(expected.trim(), actual == null ? null : actual.trim())) {
            result.addFailure(field + ": expected='" + expected + "' actual='" + actual + "'");
        }
    }

    private void assertOptionalDecimal(TestResult result, String field,
                                        String expected, BigDecimal actual) {
        if (blank(expected)) return;
        try {
            BigDecimal expDec = new BigDecimal(expected.trim());
            if (actual == null || expDec.compareTo(actual) != 0) {
                result.addFailure(field + ": expected='" + expected + "' actual='" + actual + "'");
            }
        } catch (NumberFormatException e) {
            result.addFailure(field + ": expected value '" + expected + "' is not a valid decimal");
        }
    }

    private void assertUuid(TestResult result, String field, String value) {
        if (!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            result.addFailure(field + ": '" + value + "' is not a valid UUID");
        }
    }

    private boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
