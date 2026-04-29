package com.fx.csvtest.xml;

import com.fx.csvtest.model.TestCase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Constructs a pacs.009.001.08 XML string from a {@link TestCase}.
 *
 * <h3>Behaviour</h3>
 * <ul>
 *   <li>Required fields are taken from the TestCase.</li>
 *   <li>If {@code creDtTm} is blank, the current timestamp is injected automatically.</li>
 *   <li>If {@code msgId} is blank, a synthetic ID is generated from the txId.</li>
 *   <li>Elements listed in {@link TestCase#getOmitElements()} are excluded
 *       from the XML to trigger XSD validation failures.</li>
 *   <li>{@link TestCase#getRawXmlOverrides()} allows injecting verbatim invalid
 *       XML snippets (e.g. bad currency codes, negative amounts).</li>
 * </ul>
 */
@Component
@Slf4j
public class Pacs009XmlFactory {

    private static final String NS = "urn:iso:std:iso:20022:tech:xsd:pacs.009.001.08";

    /**
     * Builds the pacs.009 XML string from the given test case.
     *
     * @param tc the test case definition
     * @return fully formed (or intentionally malformed) XML string
     */
    public String build(TestCase tc) {
        // Parse rawXmlOverrides into a lookup map: elementName → raw XML snippet
        Map<String, String> overrides = parseOverrides(tc.getRawXmlOverrides());

        String msgId   = blank(tc.getMsgId())   ? "MSG-" + tc.getTxId() : tc.getMsgId();
        String creDtTm = blank(tc.getCreDtTm()) ? LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")) : tc.getCreDtTm();
        String nbOfTxs = blank(tc.getNbOfTxs()) ? "1" : tc.getNbOfTxs();
        String sttlmMtd = blank(tc.getSettlementMethod()) ? "GROS" : tc.getSettlementMethod();
        String chrgBr   = blank(tc.getChargeBearer())     ? "SHAR" : tc.getChargeBearer();
        String debtorAgt  = blank(tc.getDebtorAgentBic())   ? "BOFAUS3N" : tc.getDebtorAgentBic();
        String creditorAgt = blank(tc.getCreditorAgentBic()) ? "CHASUS33" : tc.getCreditorAgentBic();

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<Document xmlns=\"").append(NS).append("\">\n");
        sb.append("  <FIToFICstmrCdtTrf>\n");
        sb.append("    <GrpHdr>\n");
        if (!tc.shouldOmit("MsgId"))
            sb.append(el(2, "MsgId", overrides.getOrDefault("MsgId", msgId)));
        sb.append(el(2, "CreDtTm", creDtTm));
        sb.append(el(2, "NbOfTxs", nbOfTxs));
        sb.append("      <SttlmInf>\n");
        if (!tc.shouldOmit("SttlmMtd"))
            sb.append(el(3, "SttlmMtd", overrides.getOrDefault("SttlmMtd", sttlmMtd)));
        sb.append("      </SttlmInf>\n");
        sb.append("    </GrpHdr>\n");

        sb.append("    <CdtTrfTxInf>\n");
        sb.append("      <PmtId>\n");
        if (!blank(tc.getInstrId()) && !tc.shouldOmit("InstrId"))
            sb.append(el(3, "InstrId", tc.getInstrId()));
        if (!tc.shouldOmit("EndToEndId"))
            sb.append(el(3, "EndToEndId", overrides.getOrDefault("EndToEndId",
                    blank(tc.getEndToEndId()) ? "E2E-" + tc.getTxId() : tc.getEndToEndId())));
        if (!tc.shouldOmit("TxId"))
            sb.append(el(3, "TxId", overrides.getOrDefault("TxId", tc.getTxId())));
        if (!blank(tc.getUetr()) && !tc.shouldOmit("UETR"))
            sb.append(el(3, "UETR", tc.getUetr()));
        sb.append("      </PmtId>\n");

        // IntrBkSttlmAmt – allow raw override for invalid currency/amount tests
        if (!tc.shouldOmit("IntrBkSttlmAmt")) {
            if (overrides.containsKey("IntrBkSttlmAmt")) {
                sb.append("      ").append(overrides.get("IntrBkSttlmAmt")).append("\n");
            } else {
                sb.append("      <IntrBkSttlmAmt Ccy=\"")
                  .append(blank(tc.getCurrency()) ? "USD" : tc.getCurrency())
                  .append("\">")
                  .append(blank(tc.getAmount()) ? "1000000.00" : tc.getAmount())
                  .append("</IntrBkSttlmAmt>\n");
            }
        }

        if (!tc.shouldOmit("IntrBkSttlmDt") && !blank(tc.getSettlementDate()))
            sb.append(el(2, "IntrBkSttlmDt", overrides.getOrDefault("IntrBkSttlmDt", tc.getSettlementDate())));
        else if (!tc.shouldOmit("IntrBkSttlmDt") && !overrides.containsKey("IntrBkSttlmDt"))
            sb.append(el(2, "IntrBkSttlmDt", "2024-04-17"));   // default

        if (!blank(tc.getExchangeRate()) && !tc.shouldOmit("XchgRate"))
            sb.append(el(2, "XchgRate", tc.getExchangeRate()));

        if (!tc.shouldOmit("ChrgBr"))
            sb.append(el(2, "ChrgBr", overrides.getOrDefault("ChrgBr", chrgBr)));

        // Debtor
        if (!tc.shouldOmit("Dbtr")) {
            sb.append("      <Dbtr><FinInstnId>");
            if (!blank(tc.getDebtorBic()))
                sb.append("<BICFI>").append(overrides.getOrDefault("DebtorBICFI", tc.getDebtorBic())).append("</BICFI>");
            if (!blank(tc.getDebtorName()))
                sb.append("<Nm>").append(tc.getDebtorName()).append("</Nm>");
            sb.append("</FinInstnId></Dbtr>\n");
        }
        if (!blank(tc.getDebtorIban()) && !tc.shouldOmit("DbtrAcct"))
            sb.append("      <DbtrAcct><Id><IBAN>").append(tc.getDebtorIban()).append("</IBAN></Id></DbtrAcct>\n");

        sb.append("      <DbtrAgt><FinInstnId><BICFI>")
          .append(overrides.getOrDefault("DbtrAgtBICFI", debtorAgt))
          .append("</BICFI></FinInstnId></DbtrAgt>\n");

        sb.append("      <CdtrAgt><FinInstnId><BICFI>")
          .append(overrides.getOrDefault("CdtrAgtBICFI", creditorAgt))
          .append("</BICFI></FinInstnId></CdtrAgt>\n");

        // Creditor
        if (!tc.shouldOmit("Cdtr")) {
            sb.append("      <Cdtr><FinInstnId>");
            if (!blank(tc.getCreditorBic()))
                sb.append("<BICFI>").append(overrides.getOrDefault("CreditorBICFI", tc.getCreditorBic())).append("</BICFI>");
            if (!blank(tc.getCreditorName()))
                sb.append("<Nm>").append(tc.getCreditorName()).append("</Nm>");
            sb.append("</FinInstnId></Cdtr>\n");
        }
        if (!blank(tc.getCreditorIban()) && !tc.shouldOmit("CdtrAcct"))
            sb.append("      <CdtrAcct><Id><IBAN>").append(tc.getCreditorIban()).append("</IBAN></Id></CdtrAcct>\n");

        if (!blank(tc.getPurposeCode()) && !tc.shouldOmit("Purp"))
            sb.append("      <Purp><Cd>").append(tc.getPurposeCode()).append("</Cd></Purp>\n");
        if (!blank(tc.getRemittanceInfo()) && !tc.shouldOmit("RmtInf"))
            sb.append("      <RmtInf><Ustrd>").append(tc.getRemittanceInfo()).append("</Ustrd></RmtInf>\n");

        sb.append("    </CdtTrfTxInf>\n");
        sb.append("  </FIToFICstmrCdtTrf>\n");
        sb.append("</Document>");

        String xml = sb.toString();
        log.debug("Built XML for test {}: {} chars", tc.getTestId(), xml.length());
        return xml;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Parse rawXmlOverrides string.
     * Format: {@code ElementName=rawXmlFragment||ElementName2=rawXmlFragment2}
     */
    private Map<String, String> parseOverrides(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        if (blank(raw)) return map;
        for (String part : raw.split("\\|\\|")) {
            int idx = part.indexOf('=');
            if (idx > 0) {
                map.put(part.substring(0, idx).trim(), part.substring(idx + 1).trim());
            }
        }
        return map;
    }

    private String el(int indent, String name, String value) {
        String pad = "  ".repeat(indent);
        return pad + "<" + name + ">" + value + "</" + name + ">\n";
    }

    private boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
