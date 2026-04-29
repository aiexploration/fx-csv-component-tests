package com.fx.csvtest.db;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Persisted correlation record for each test message dispatched during the suite.
 *
 * <p>Written immediately after the message is placed on {@code fx.pacs009.inbound},
 * then updated once the AUT has processed it. This allows the test framework to
 * correlate inbound txId → outbound domain payment UUID without relying on
 * fragile queue ordering.
 */
@Entity
@Table(name = "test_execution",
        indexes = @Index(name = "idx_te_txid", columnList = "tx_id"))
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestExecutionRecord {

    public enum State {
        SENT,       // message dispatched to inbound queue
        PROCESSED,  // received on valid queue
        INVALID,    // received on invalid queue
        TIMEOUT,    // never received within the timeout window
        ERROR       // unexpected framework error
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "test_id", nullable = false, length = 50)
    private String testId;

    @Column(name = "category", length = 100)
    private String category;

    @Column(name = "tx_id", nullable = false, length = 100)
    private String txId;

    @Column(name = "msg_id", length = 100)
    private String msgId;

    @Column(name = "expected_outcome", length = 10)
    private String expectedOutcome;   // VALID or INVALID

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private State state;

    /** UUID assigned by the AUT in the PaymentMessage table. */
    @Column(name = "aut_payment_id", length = 36)
    private String autPaymentId;

    @Column(name = "received_domain_xml", columnDefinition = "TEXT")
    private String receivedDomainXml;

    @Column(name = "received_invalid_xml", columnDefinition = "TEXT")
    private String receivedInvalidXml;

    @Column(name = "aut_db_status", length = 20)
    private String autDbStatus;

    @Column(name = "aut_validation_errors", length = 2000)
    private String autValidationErrors;

    @Column(name = "result_status", length = 10)
    private String resultStatus;   // PASS / FAIL / ERROR

    @Column(name = "failure_details", columnDefinition = "TEXT")
    private String failureDetails;

    @Column(name = "duration_ms")
    private Long durationMs;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
