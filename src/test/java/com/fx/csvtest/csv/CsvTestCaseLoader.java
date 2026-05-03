package com.fx.csvtest.csv;

import com.fx.csvtest.model.ExpectedOutcome;
import com.fx.csvtest.model.TestCase;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.RFC4180Parser;
import com.opencsv.RFC4180ParserBuilder;
import com.opencsv.exceptions.CsvValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.FileReader;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Scans the configured {@code test-data-dir} for all {@code *.csv} files and
 * parses them into {@link TestCase} objects.
 *
 * <h3>CSV format</h3>
 * <ul>
 *   <li>First row must be a header row matching the field names below (order-independent).</li>
 *   <li>Rows starting with {@code #} are treated as comments and skipped.</li>
 *   <li>Blank rows are skipped.</li>
 *   <li>Fields are trimmed; blank optional fields are treated as absent.</li>
 * </ul>
 */
@Component
@Slf4j
public class CsvTestCaseLoader {

    @Value("${fx.component.test.test-data-dir:src/test/resources/test-data}")
    private String testDataDir;

    /** When set, only this file is loaded instead of all *.csv in the directory. */
    @Value("${fx.component.test.test-data-file:}")
    private String testDataFile;

    /**
     * Loads test cases. If {@code fx.component.test.test-data-file} is set, loads
     * only that file; otherwise loads all {@code *.csv} files from the directory.
     *
     * @return ordered list of test cases, preserving file and row order
     */
    public List<TestCase> loadAll() throws IOException {
        if (testDataFile != null && !testDataFile.isBlank()) {
            return loadSingleFile(testDataFile);
        }

        Path dir = Paths.get(testDataDir);
        if (!Files.exists(dir)) {
            throw new IllegalStateException("Test data directory not found: " + dir.toAbsolutePath());
        }

        List<TestCase> all = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.csv")) {
            List<Path> files = new ArrayList<>();
            stream.forEach(files::add);
            files.sort(Comparator.naturalOrder());   // deterministic alphabetical order

            for (Path file : files) {
                log.info("Loading test cases from: {}", file.getFileName());
                List<TestCase> loaded = loadFile(file);
                log.info("  → {} test cases loaded", loaded.size());
                all.addAll(loaded);
            }
        }
        log.info("Total test cases loaded: {}", all.size());
        return all;
    }

    private List<TestCase> loadSingleFile(String fileName) throws IOException {
        // Accept absolute path or filename relative to testDataDir
        Path file = Paths.get(fileName);
        if (!file.isAbsolute() || !Files.exists(file)) {
            file = Paths.get(testDataDir).resolve(fileName);
        }
        if (!Files.exists(file)) {
            throw new IllegalStateException("Test data file not found: " + file.toAbsolutePath());
        }
        log.info("Loading test cases from: {}", file.getFileName());
        List<TestCase> loaded = loadFile(file);
        log.info("Total test cases loaded: {}", loaded.size());
        return loaded;
    }

    // ── Private ───────────────────────────────────────────────────────────

    private List<TestCase> loadFile(Path file) throws IOException {
        List<TestCase> cases = new ArrayList<>();
        RFC4180Parser parser = new RFC4180ParserBuilder().build();

        try (CSVReader reader = new CSVReaderBuilder(new FileReader(file.toFile()))
                .withCSVParser(parser).build()) {

            String[] header = null;
            String[] row;
            int rowNum = 0;

            while ((row = reader.readNext()) != null) {
                rowNum++;
                if (row.length == 0 || (row.length == 1 && row[0].trim().isEmpty())) continue;
                if (row[0].trim().startsWith("#")) continue;   // comment row

                if (header == null) {
                    header = trim(row);
                    continue;
                }

                String[] normalizedRow = normalizeRow(row, header.length, rowNum, file);
                Map<String, String> m = toMap(header, trim(normalizedRow));
                try {
                    cases.add(map(m));
                } catch (Exception e) {
                    log.warn("Skipping malformed row {} in {}: {}", rowNum, file.getFileName(), e.getMessage());
                }
            }
        } catch (CsvValidationException e) {
            throw new IOException("Invalid CSV in " + file.getFileName() + ": " + e.getMessage(), e);
        }
        return cases;
    }

    private TestCase map(Map<String, String> m) {
        return TestCase.builder()
                // ── Metadata
                .testId(req(m, "testId"))
                .description(opt(m, "description"))
                .category(opt(m, "category", "General"))
                .expectedOutcome(ExpectedOutcome.valueOf(req(m, "expectedOutcome").toUpperCase()))
                .expectedErrorContains(opt(m, "expectedErrorContains"))
                // ── Group Header
                .msgId(opt(m, "msgId"))
                .creDtTm(opt(m, "creDtTm"))
                .nbOfTxs(opt(m, "nbOfTxs", "1"))
                .settlementMethod(opt(m, "settlementMethod", "GROS"))
                // ── Payment ID
                .instrId(opt(m, "instrId"))
                .endToEndId(opt(m, "endToEndId"))
                .txId(req(m, "txId"))
                .uetr(opt(m, "uetr"))
                // ── Settlement
                .amount(opt(m, "amount"))
                .currency(opt(m, "currency"))
                .settlementDate(opt(m, "settlementDate"))
                .exchangeRate(opt(m, "exchangeRate"))
                .chargeBearer(opt(m, "chargeBearer", "SHAR"))
                // ── Debtor
                .debtorBic(opt(m, "debtorBic"))
                .debtorName(opt(m, "debtorName"))
                .debtorIban(opt(m, "debtorIban"))
                .debtorAgentBic(opt(m, "debtorAgentBic"))
                // ── Creditor
                .creditorAgentBic(opt(m, "creditorAgentBic"))
                .creditorBic(opt(m, "creditorBic"))
                .creditorName(opt(m, "creditorName"))
                .creditorIban(opt(m, "creditorIban"))
                // ── Optional
                .purposeCode(opt(m, "purposeCode"))
                .remittanceInfo(opt(m, "remittanceInfo"))
                // ── XML overrides
                .omitElements(opt(m, "omitElements"))
                .rawXmlOverrides(opt(m, "rawXmlOverrides"))
                // ── Expected assertions
                .expectedPaymentStatus(opt(m, "expectedPaymentStatus", "PROCESSED"))
                .expectedSettlementAmount(opt(m, "expectedSettlementAmount"))
                .expectedSettlementCurrency(opt(m, "expectedSettlementCurrency"))
                .expectedSettlementDate(opt(m, "expectedSettlementDate"))
                .expectedSettlementMethod(opt(m, "expectedSettlementMethod"))
                .expectedExchangeRate(opt(m, "expectedExchangeRate"))
                .expectedDebtorBic(opt(m, "expectedDebtorBic"))
                .expectedCreditorBic(opt(m, "expectedCreditorBic"))
                .expectedDebtorIban(opt(m, "expectedDebtorIban"))
                .expectedCreditorIban(opt(m, "expectedCreditorIban"))
                .expectedDebtorName(opt(m, "expectedDebtorName"))
                .expectedCreditorName(opt(m, "expectedCreditorName"))
                .expectedDebtorAgentBic(opt(m, "expectedDebtorAgentBic"))
                .expectedCreditorAgentBic(opt(m, "expectedCreditorAgentBic"))
                .expectedChargeBearer(opt(m, "expectedChargeBearer"))
                .expectedPurposeCode(opt(m, "expectedPurposeCode"))
                .expectedRemittanceInfo(opt(m, "expectedRemittanceInfo"))
                .expectedUetr(opt(m, "expectedUetr"))
                .expectedEndToEndId(opt(m, "expectedEndToEndId"))
                .build();
    }

    // ── Utilities ──────────────────────────────────────────────────────────

    private String req(Map<String, String> m, String key) {
        String v = m.get(key);
        if (v == null || v.isBlank())
            throw new IllegalArgumentException("Required column '" + key + "' is missing or blank");
        return v;
    }

    private String opt(Map<String, String> m, String key) {
        String v = m.get(key);
        return (v == null || v.isBlank()) ? null : v;
    }

    private String opt(Map<String, String> m, String key, String defaultValue) {
        String v = m.get(key);
        return (v == null || v.isBlank()) ? defaultValue : v;
    }

    private Map<String, String> toMap(String[] headers, String[] values) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < headers.length; i++) {
            map.put(headers[i], i < values.length ? values[i] : "");
        }
        return map;
    }

    private String[] normalizeRow(String[] row, int headerLength, int rowNum, Path file) {
        if (row.length > headerLength) {
            boolean extraValuesBlank = true;
            for (int i = headerLength; i < row.length; i++) {
                if (!row[i].trim().isEmpty()) {
                    extraValuesBlank = false;
                    break;
                }
            }
            if (extraValuesBlank) {
                log.warn("Row {} in {} has {} extra empty columns; trimming to header length",
                        rowNum, file.getFileName(), row.length - headerLength);
                return Arrays.copyOf(row, headerLength);
            }
            throw new IllegalArgumentException(String.format(
                    "Malformed row %d in %s: expected %d columns but found %d",
                    rowNum, file.getFileName(), headerLength, row.length));
        }
        return row;
    }

    private String[] trim(String[] arr) {
        return Arrays.stream(arr).map(String::trim).toArray(String[]::new);
    }
}
