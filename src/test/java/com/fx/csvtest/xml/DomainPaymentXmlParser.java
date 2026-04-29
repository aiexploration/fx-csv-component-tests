package com.fx.csvtest.xml;

import com.fx.csvtest.model.DomainPayment;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.math.BigDecimal;

@Component
public class DomainPaymentXmlParser {

    public DomainPayment parse(String xml) {
        Document document = parseDocument(xml);
        return DomainPayment.builder()
                .paymentId(text(document, "PaymentId"))
                .originalMessageId(text(document, "OriginalMessageId"))
                .transactionId(text(document, "TransactionId"))
                .endToEndId(text(document, "EndToEndId"))
                .uetr(text(document, "UETR"))
                .settlementAmount(decimal(document, "SettlementAmount"))
                .settlementCurrency(text(document, "SettlementCurrency"))
                .settlementDate(text(document, "SettlementDate"))
                .settlementMethod(text(document, "SettlementMethod"))
                .exchangeRate(decimal(document, "ExchangeRate"))
                .debtorBic(text(document, "DebtorBIC"))
                .debtorName(text(document, "DebtorName"))
                .debtorIban(text(document, "DebtorIBAN"))
                .debtorAgentBic(text(document, "DebtorAgentBIC"))
                .creditorBic(text(document, "CreditorBIC"))
                .creditorName(text(document, "CreditorName"))
                .creditorIban(text(document, "CreditorIBAN"))
                .creditorAgentBic(text(document, "CreditorAgentBIC"))
                .chargeBearer(text(document, "ChargeBearer"))
                .purposeCode(text(document, "PurposeCode"))
                .remittanceInfo(text(document, "RemittanceInfo"))
                .processingTimestamp(text(document, "ProcessingTimestamp"))
                .paymentStatus(text(document, "PaymentStatus"))
                .build();
    }

    private Document parseDocument(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not parse DomainPayment XML", e);
        }
    }

    private String text(Document document, String localName) {
        NodeList nodes = document.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0) {
            return null;
        }
        String value = nodes.item(0).getTextContent();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BigDecimal decimal(Document document, String localName) {
        String value = text(document, localName);
        return value == null ? null : new BigDecimal(value);
    }
}
