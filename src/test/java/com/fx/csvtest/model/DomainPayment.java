package com.fx.csvtest.model;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

/**
 * Local black-box representation of the DomainPayment XML emitted by SwiftPay.
 */
@Value
@Builder
public class DomainPayment {
    String paymentId;
    String originalMessageId;
    String transactionId;
    String endToEndId;
    String uetr;
    BigDecimal settlementAmount;
    String settlementCurrency;
    String settlementDate;
    String settlementMethod;
    BigDecimal exchangeRate;
    String debtorBic;
    String debtorName;
    String debtorIban;
    String debtorAgentBic;
    String creditorBic;
    String creditorName;
    String creditorIban;
    String creditorAgentBic;
    String chargeBearer;
    String purposeCode;
    String remittanceInfo;
    String processingTimestamp;
    String paymentStatus;
}
