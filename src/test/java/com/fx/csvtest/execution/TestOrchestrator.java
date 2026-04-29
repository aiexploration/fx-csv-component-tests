package com.fx.csvtest.execution;

import com.fx.csvtest.assertion.DomainPaymentAsserter;
import com.fx.csvtest.db.TestExecutionRecord;
import com.fx.csvtest.db.TestExecutionRepository;
import com.fx.csvtest.model.TestCase;
import com.fx.csvtest.model.TestResult;
import com.fx.payment.config.JmsConfig;
import com.fx.payment.entity.PaymentMessage;
import com.fx.payment.entity.PaymentStatus;
import com.fx.payment.model.domain.DomainPayment;
import com.fx.payment.repository.PaymentMessageRepository;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.time.LocalDateTime;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates the end-to-end execution of one {@link TestCase}:
 *
 * <ol>
 *   <li>Record the test dispatch in the local DB.</li>
 *   <li>Send the pacs.009 XML to {@code fx.pacs009.inbound}.</li>
 *   <li>Wait (via Awaitility + AUT's exposed embedded H2 DB) for the AUT to process it.</li>
   *   <li>For VALID tests: browse the domain payment from {@code fx.payment.valid}
   *       and run assertion checks.</li>
 *   <li>For INVALID tests: verify the DB record has INVALID status and the
 *       raw message appears on {@code fx.payment.invalid}.</li>
 *   <li>Update the correlation DB record with the outcome.</li>
 *   <li>Return a {@link TestResult}.</li>
 * </ol>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TestOrchestrator {

    private final JmsTemplate jmsTemplate;
    private final PaymentMessageRepository autPaymentRepo;     // AUT's DB
    private final TestExecutionRepository testExecRepo;         // test correlation table
    private final DomainPaymentAsserter asserter;

    @Value("${fx.component.test.message-timeout-seconds:15}")
    private int timeoutSeconds;

    private static final JAXBContext DOMAIN_JAXB;
    static {
        try {
            DOMAIN_JAXB = JAXBContext.newInstance(DomainPayment.class);
        } catch (JAXBException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Executes a single test case and returns the result.
     *
     * @param tc      the test case to execute
     * @param sentXml the pre-built pacs.009 XML string
     * @return populated {@link TestResult}
     */
    public TestResult execute(TestCase tc, String sentXml) {
        long startMs = System.currentTimeMillis();
        log.info("▶ Executing [{}] {} ({})", tc.getTestId(), tc.getDescription(), tc.getExpectedOutcome());

        // Save dispatch record
        TestExecutionRecord execRecord = testExecRepo.save(TestExecutionRecord.builder()
                .testId(tc.getTestId())
                .category(tc.getCategory())
                .txId(tc.getTxId())
                .msgId(blank(tc.getMsgId()) ? "MSG-" + tc.getTxId() : tc.getMsgId())
                .expectedOutcome(tc.getExpectedOutcome().name())
                .state(TestExecutionRecord.State.SENT)
                .build());

        TestResult result = TestResult.builder()
                .testId(tc.getTestId())
                .category(tc.getCategory())
                .description(tc.getDescription())
                .expectedOutcome(tc.getExpectedOutcome())
                .txId(tc.getTxId())
                .sentXml(sentXml)
                .executedAt(LocalDateTime.now())
                .build();

        try {
            Set<UUID> existingPaymentIds = existingPaymentIdsForTxId(tc.getTxId());

            // ── 1. Send to inbound queue ──────────────────────────────────
            jmsTemplate.convertAndSend(JmsConfig.INBOUND_QUEUE, sentXml);
            log.debug("[{}] Sent to {}", tc.getTestId(), JmsConfig.INBOUND_QUEUE);

            // ── 2. Route to VALID or INVALID assertion path ───────────────
            if (tc.isValid()) {
                handleValidCase(tc, result, execRecord, existingPaymentIds);
            } else {
                handleInvalidCase(tc, result, execRecord);
            }

        } catch (ConditionTimeoutException e) {
            result.setStatus(TestResult.Status.FAIL);
            result.addFailure("Timeout after " + timeoutSeconds + "s waiting for AUT to process message. " + e.getMessage());
            execRecord.setState(TestExecutionRecord.State.TIMEOUT);
            execRecord.setResultStatus("FAIL");
            log.error("[{}] TIMEOUT – {}", tc.getTestId(), e.getMessage());

        } catch (Exception e) {
            result.setStatus(TestResult.Status.ERROR);
            result.setErrorMessage(e.getMessage());
            execRecord.setState(TestExecutionRecord.State.ERROR);
            execRecord.setResultStatus("ERROR");
            log.error("[{}] UNEXPECTED ERROR – {}", tc.getTestId(), e.getMessage(), e);
        }

        long durationMs = System.currentTimeMillis() - startMs;
        result.setDurationMs(durationMs);
        execRecord.setDurationMs(durationMs);
        execRecord.setCompletedAt(LocalDateTime.now());
        testExecRepo.save(execRecord);

        log.info("{} [{}] {} ({}ms)",
                result.getStatus() == TestResult.Status.PASS ? "✓" : "✗",
                tc.getTestId(), result.getStatus(), durationMs);
        return result;
    }

    // ── Valid path ─────────────────────────────────────────────────────────

    private void handleValidCase(
            TestCase tc,
            TestResult result,
            TestExecutionRecord exec,
            Set<UUID> existingPaymentIds) {
        // Wait for AUT DB record to reach PROCESSED
        Awaitility.await("[" + tc.getTestId() + "] PROCESSED in AUT DB")
                .atMost(timeoutSeconds, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .until(() -> {
                    Optional<PaymentMessage> r = latestNewPaymentForTxId(tc.getTxId(), existingPaymentIds);
                    return r.isPresent() && r.get().getStatus() == PaymentStatus.PROCESSED;
                });

        PaymentMessage autRecord = latestNewPaymentForTxId(tc.getTxId(), existingPaymentIds).orElseThrow();
        result.setPersistedPaymentId(autRecord.getId().toString());
        result.setPersistedStatus(autRecord.getStatus().name());
        exec.setAutPaymentId(autRecord.getId().toString());
        exec.setAutDbStatus(autRecord.getStatus().name());

        // Browse domain payment from valid queue - match by TransactionId without consuming it.
        DomainPayment domain = browseForTxId(JmsConfig.VALID_QUEUE, tc.getTxId());
        if (domain == null) {
            result.setStatus(TestResult.Status.FAIL);
            result.addFailure("No domain payment found on " + JmsConfig.VALID_QUEUE
                    + " with TransactionId=" + tc.getTxId());
            exec.setState(TestExecutionRecord.State.PROCESSED);
            exec.setResultStatus("FAIL");
            return;
        }

        String domainXml = serializeDomain(domain);
        result.setReceivedDomainXml(domainXml);
        exec.setReceivedDomainXml(domainXml);
        exec.setState(TestExecutionRecord.State.PROCESSED);

        // Run domain payment assertions
        asserter.assertAll(tc, domain, result);

        // Also assert UUID consistency: domain PaymentId must match AUT DB id
        if (domain.getPaymentId() != null && !domain.getPaymentId().equals(autRecord.getId().toString())) {
            result.addFailure("UUID mismatch: domain.PaymentId='" + domain.getPaymentId()
                    + "' != AUT DB id='" + autRecord.getId() + "'");
        }

        finalizeResult(result, exec);
    }

    // ── Invalid path ───────────────────────────────────────────────────────

    private void handleInvalidCase(TestCase tc, TestResult result, TestExecutionRecord exec) {
        // Wait for a new INVALID record to appear in AUT DB
        long invalidsBefore = autPaymentRepo.findByStatus(PaymentStatus.INVALID).size();

        Awaitility.await("[" + tc.getTestId() + "] INVALID record in AUT DB")
                .atMost(timeoutSeconds, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .until(() -> autPaymentRepo.findByStatus(PaymentStatus.INVALID).size() > invalidsBefore);

        // Get most recent INVALID record
        List<PaymentMessage> invalids = autPaymentRepo.findByStatus(PaymentStatus.INVALID);
        PaymentMessage autRecord = invalids.stream()
                .max(java.util.Comparator.comparing(PaymentMessage::getCreatedAt))
                .orElseThrow();

        result.setPersistedStatus(autRecord.getStatus().name());
        result.setPersistedValidationErrors(autRecord.getValidationErrors());
        exec.setAutDbStatus(autRecord.getStatus().name());
        exec.setAutValidationErrors(autRecord.getValidationErrors());

        // Verify the message also appeared on the invalid queue without consuming it.
        String invalidXml = browseTextMessageContaining(JmsConfig.INVALID_QUEUE, tc.getTxId());
        if (invalidXml == null) {
            result.addFailure("No message found on " + JmsConfig.INVALID_QUEUE
                    + " with TransactionId=" + tc.getTxId());
        } else {
            result.setReceivedInvalidXml(invalidXml);
            exec.setReceivedInvalidXml(invalidXml.length() > 2000 ? invalidXml.substring(0, 2000) + "..." : invalidXml);
        }

        // Check expected error substring if specified in CSV
        if (!blank(tc.getExpectedErrorContains())) {
            String errors = autRecord.getValidationErrors();
            if (errors == null || !errors.toLowerCase().contains(tc.getExpectedErrorContains().toLowerCase())) {
                result.addFailure("ValidationErrors: expected to contain '"
                        + tc.getExpectedErrorContains() + "' but was: '"
                        + errors + "'");
            }
        }

        exec.setState(TestExecutionRecord.State.INVALID);
        finalizeResult(result, exec);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private Set<UUID> existingPaymentIdsForTxId(String txId) {
        return autPaymentRepo.findAll().stream()
                .filter(payment -> txId.equals(payment.getTransactionId()))
                .map(PaymentMessage::getId)
                .collect(java.util.stream.Collectors.toSet());
    }

    private Optional<PaymentMessage> latestNewPaymentForTxId(String txId, Set<UUID> existingPaymentIds) {
        return autPaymentRepo.findAll().stream()
                .filter(payment -> txId.equals(payment.getTransactionId()))
                .filter(payment -> !existingPaymentIds.contains(payment.getId()))
                .max(java.util.Comparator.comparing(PaymentMessage::getCreatedAt));
    }

    private DomainPayment browseForTxId(String queue, String txId) {
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        while (System.currentTimeMillis() < deadline) {
            DomainPayment found = jmsTemplate.browse(queue, (session, browser) -> {
                Enumeration<?> messages = browser.getEnumeration();
                while (messages.hasMoreElements()) {
                    Message raw = (Message) messages.nextElement();
                    try {
                        String xml = ((TextMessage) raw).getText();
                        DomainPayment dp = parseDomain(xml);
                        if (txId.equals(dp.getTransactionId())) {
                            return dp;
                        }
                        log.debug("Skipped unrelated domain payment txId={} (looking for {})",
                                dp.getTransactionId(), txId);
                    } catch (Exception e) {
                        log.warn("Failed to parse domain payment message: {}", e.getMessage());
                    }
                }
                return null;
            });
            if (found != null) {
                return found;
            }
            sleepBeforeNextBrowse();
        }
        return null;
    }

    private String browseTextMessageContaining(String queue, String expectedText) {
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        while (System.currentTimeMillis() < deadline) {
            String found = jmsTemplate.browse(queue, (session, browser) -> {
                Enumeration<?> messages = browser.getEnumeration();
                while (messages.hasMoreElements()) {
                    Message raw = (Message) messages.nextElement();
                    try {
                        String text = ((TextMessage) raw).getText();
                        if (text.contains(expectedText)) {
                            return text;
                        }
                    } catch (Exception e) {
                        log.warn("Failed to read invalid queue message: {}", e.getMessage());
                    }
                }
                return null;
            });
            if (found != null) {
                return found;
            }
            sleepBeforeNextBrowse();
        }
        return null;
    }

    private void sleepBeforeNextBrowse() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private DomainPayment parseDomain(String xml) throws JAXBException {
        return (DomainPayment) DOMAIN_JAXB.createUnmarshaller().unmarshal(new StringReader(xml));
    }

    private String serializeDomain(DomainPayment domain) {
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            DOMAIN_JAXB.createMarshaller().marshal(domain, sw);
            return sw.toString();
        } catch (JAXBException e) {
            return "<serialization-error/>";
        }
    }

    private void finalizeResult(TestResult result, TestExecutionRecord exec) {
        if (result.getAssertionFailures().isEmpty() && result.getErrorMessage() == null) {
            result.setStatus(TestResult.Status.PASS);
            exec.setResultStatus("PASS");
        } else {
            result.setStatus(TestResult.Status.FAIL);
            exec.setResultStatus("FAIL");
            exec.setFailureDetails(String.join("\n", result.getAssertionFailures()));
        }
    }

    private boolean blank(String s) { return s == null || s.isBlank(); }
}
