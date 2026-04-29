# FX CSV Component Test Suite

A **CSV-driven component test framework** for the `fx-payment-processor` ISO 20022 pacs.009 engine. Each test case is a row in a CSV file — no Java code required to add new scenarios.

---

## How It Works

```
┌─────────────────────────────────────────────────────────────────────┐
│                     CSV Component Test Suite                        │
│                                                                     │
│  *.csv files          Pacs009XmlFactory          JMS Inbound Queue  │
│  (test-data/)  ──►   builds pacs.009 XML  ──►   fx.pacs009.inbound │
│                                                        │            │
│                                                        ▼            │
│                                               ┌──────────────────┐ │
│                                               │  fx-payment-     │ │
│                                               │  processor (AUT) │ │
│                                               │  (embedded, same │ │
│                                               │   Spring context)│ │
│                                               └──────────────────┘ │
│                                                    │         │      │
│                                         VALID ◄────┘         └────► INVALID │
│                                         queue                queue  │
│                                            │                   │    │
│                                            ▼                   ▼    │
│                              DomainPaymentAsserter        assert on │
│                              checks each expected         invalid   │
│                              field from CSV               queue +DB │
│                                            │                        │
│                                            ▼                        │
│                           ┌───────────────────────────────┐        │
│                           │   HTML + CSV Report            │        │
│                           │   target/component-test-report/│        │
│                           └───────────────────────────────┘        │
└─────────────────────────────────────────────────────────────────────┘
```

### Processing Pipeline

1. **Load** — All `*.csv` files in `src/test/resources/test-data/` are loaded alphabetically.
2. **Build** — `Pacs009XmlFactory` converts each CSV row into a pacs.009.001.08 XML string. Omission flags and raw XML overrides allow injecting deliberately invalid content.
3. **Send** — The XML is published to `fx.pacs009.inbound`.
4. **Wait** — `TestOrchestrator` polls the AUT's H2 database (via JPA) until the record reaches `PROCESSED` or `INVALID` status (up to 15 seconds per test).
5. **Receive** — For VALID tests, the domain payment XML is drained from `fx.payment.valid`, correlating on `TransactionId`. For INVALID tests, the message is read from `fx.payment.invalid`.
6. **Assert** — `DomainPaymentAsserter` compares every non-blank expected column from the CSV against the received domain payment. Timestamps are validated for ISO-8601 format and freshness (≤5 minutes old) rather than exact match.
7. **Record** — Each test's state is persisted in the test framework's own H2 table (`test_execution`) for DB-level correlation and auditability.
8. **Report** — HTML and CSV reports are written to `target/component-test-report/`.

---

## One-Click Execution

```bash
./run-tests.sh            # install AUT + run all tests
./run-tests.sh --open-report  # same + open HTML report in browser
```

Or via Make:

```bash
make run          # install AUT + run
make run-open     # run + open report
make test         # skip AUT install (if already installed)
```

### Requirements

| Tool  | Minimum |
|-------|---------|
| Java  | 21      |
| Maven | 3.9     |

No Docker, no external broker, no external database required.

---

## CSV File Format

### File location

Place CSV files in `src/test/resources/test-data/`. Files are loaded **alphabetically** (prefix with `01-`, `02-` etc. to control order).

### Columns reference

| Column | Required | Description |
|--------|----------|-------------|
| `testId` | ✅ | Unique test identifier e.g. `HP-001` |
| `category` | ✅ | Logical group shown in the report e.g. `HappyPath`, `BoundaryAmount` |
| `description` | ✅ | Human-readable test name shown in the report |
| `expectedOutcome` | ✅ | `VALID` or `INVALID` |
| `txId` | ✅ | Correlation key — must be **unique across the entire suite** |
| `msgId` | optional | Auto-generated if blank (`MSG-{txId}`) |
| `creDtTm` | optional | Auto-generated as current timestamp if blank |
| `nbOfTxs` | optional | Defaults to `1` |
| `settlementMethod` | optional | `CLRG\|COVE\|GROS\|INDA` — defaults to `GROS` |
| `endToEndId` | optional | Defaults to `E2E-{txId}` |
| `instrId` | optional | Optional element; omitted if blank |
| `uetr` | optional | Optional UUID v4; omitted if blank |
| `amount` | optional | Defaults to `1000000.00` |
| `currency` | optional | 3-letter ISO 4217; defaults to `USD` |
| `settlementDate` | optional | YYYY-MM-DD; defaults to `2024-04-17` |
| `exchangeRate` | optional | Decimal; omitted if blank |
| `chargeBearer` | optional | `CRED\|DEBT\|SHAR\|SLEV` — defaults to `SHAR` |
| `debtorBic` | optional | BIC of the debtor institution |
| `debtorName` | optional | Optional; omitted if blank |
| `debtorIban` | optional | Optional; omitted if blank |
| `debtorAgentBic` | optional | Defaults to `BOFAUS3N` |
| `creditorAgentBic` | optional | Defaults to `CHASUS33` |
| `creditorBic` | optional | BIC of the creditor institution |
| `creditorName` | optional | Optional; omitted if blank |
| `creditorIban` | optional | Optional; omitted if blank |
| `purposeCode` | optional | e.g. `CORT`, `TREA`, `BEXP`, `INTC` |
| `remittanceInfo` | optional | Max 140 chars |
| `omitElements` | optional | Comma-separated list of elements to **remove** from XML, e.g. `TxId,EndToEndId`. Triggers XSD failures. |
| `rawXmlOverrides` | optional | Inject verbatim XML, e.g. `IntrBkSttlmAmt=<IntrBkSttlmAmt Ccy="USDX">-5</IntrBkSttlmAmt>`. Separate multiple overrides with `\|\|`. |
| `expectedErrorContains` | optional | For INVALID tests: substring expected in the DB `validationErrors` column. |

**Expected assertion columns** (all optional — blank = don't assert):

| Column | Domain Payment field asserted |
|--------|-------------------------------|
| `expectedPaymentStatus` | `PaymentStatus` (defaults to `PROCESSED`) |
| `expectedSettlementAmount` | `SettlementAmount` (decimal comparison) |
| `expectedSettlementCurrency` | `SettlementCurrency` |
| `expectedSettlementDate` | `SettlementDate` |
| `expectedSettlementMethod` | `SettlementMethod` |
| `expectedExchangeRate` | `ExchangeRate` (decimal comparison) |
| `expectedDebtorBic` | `DebtorBIC` |
| `expectedCreditorBic` | `CreditorBIC` |
| `expectedDebtorIban` | `DebtorIBAN` |
| `expectedCreditorIban` | `CreditorIBAN` |
| `expectedDebtorName` | `DebtorName` |
| `expectedCreditorName` | `CreditorName` |
| `expectedDebtorAgentBic` | `DebtorAgentBIC` |
| `expectedCreditorAgentBic` | `CreditorAgentBIC` |
| `expectedChargeBearer` | `ChargeBearer` |
| `expectedPurposeCode` | `PurposeCode` |
| `expectedRemittanceInfo` | `RemittanceInfo` |
| `expectedUetr` | `UETR` |
| `expectedEndToEndId` | `EndToEndId` |

### Always-asserted fields (regardless of CSV)

These are checked on every VALID test without needing a CSV column:

| Check | Rule |
|-------|------|
| `PaymentId` | Must be a valid UUID (assigned by AUT) |
| `OriginalMessageId` | Must not be blank |
| `TransactionId` | Must match the `txId` column (correlation key) |
| `PaymentStatus` | Must be `PROCESSED` (or `expectedPaymentStatus` if set) |
| `ProcessingTimestamp` | Must be valid ISO-8601 and **≤5 minutes old** |
| UUID consistency | `domain.PaymentId` must match the UUID stored in AUT's DB |

### Timestamp handling

The `ProcessingTimestamp` in the domain payment is generated at runtime by the AUT and cannot be predicted. The framework:

- Validates it is parseable as `yyyy-MM-dd'T'HH:mm:ss`
- Validates it is within the configured freshness window (default 5 minutes, configurable via `fx.component.test.timestamp-freshness-minutes`)
- Never does a literal string comparison

This is handled by `TimestampNormalizer.assertFresh()`.

### Comments and blank rows

- Rows starting with `#` are treated as comments and skipped.
- Blank rows are skipped.

### Example: Adding a new test case

```csv
# My new test case
MY-001,MyCategory,GBP interbank same-day settlement,VALID,,MY-TXN-001,MY-E2E-001,,,GROS,5000000.00,GBP,2024-04-17,,SHAR,HSBCGB2L,HSBC UK,,HSBCGB2L,BARCGB22,BARCGB22,Barclays,,,CORT,Same-day settlement,,PROCESSED,5000000.00,GBP,2024-04-17,GROS,,HSBCGB2L,BARCGB22,,,,,,,,SHAR,CORT,,,
```

### Example: Forcing an invalid XML element

To test a specific XSD violation, use `omitElements` or `rawXmlOverrides`:

```csv
# Omit the required TxId element
INV-001,InvalidTests,Missing TxId,INVALID,,INV-TXN-001,INV-E2E-001,,,GROS,1000000.00,USD,2024-04-17,,SHAR,BARCGB22,,,BOFAUS3N,CHASUS33,JPMSGB2L,,,TxId,,,,,,,,,,,,,,,,,

# Inject a 4-letter currency code via rawXmlOverride
INV-002,InvalidTests,Bad currency USDX,INVALID,,INV-TXN-002,INV-E2E-002,,,GROS,1000000.00,USD,2024-04-17,,SHAR,BARCGB22,,,BOFAUS3N,CHASUS33,JPMSGB2L,,,,"IntrBkSttlmAmt=<IntrBkSttlmAmt Ccy=""USDX"">1000000.00</IntrBkSttlmAmt>",,,,,,,,,,,,,,,,
```

---

## Test Data Files

| File | Category | # Tests | Coverage |
|------|----------|---------|----------|
| `01-happy-path-tests.csv` | HappyPath | 8 | Valid FX settlements across major currency pairs, all settlement methods |
| `02-boundary-amount-tests.csv` | BoundaryAmount | 12 | `minInclusive=0`, `fractionDigits=5`, negative, non-numeric |
| `03-boundary-currency-tests.csv` | BoundaryCurrency | 15 | `[A-Z]{3}` pattern, case, length |
| `04-boundary-bic-tests.csv` | BoundaryBIC | 14 | 8-char, 11-char, case, length, country code position |
| `05-boundary-identification-tests.csv` | BoundaryID | 17 | `Max35Text` min/max, UETR UUID v4 pattern |
| `06-boundary-settlement-enums-tests.csv` | BoundarySettlementMethod/ChargeBearer/Date | 24 | All enum values, invalid variants, xs:date format |
| `07-boundary-iban-exchangerate-tests.csv` | BoundaryIBAN/ExchangeRate | 13 | IBAN pattern, exchange rate precision |
| `08-domain-field-mapping-tests.csv` | DomainMapping | 8 | End-to-end field mapping verification |

**Total: ~111 test cases**

---

## Project Structure

```
fx-csv-component-tests/
├── pom.xml
├── run-tests.sh                              ← one-click runner
├── Makefile
├── README.md
└── src/test/
    ├── java/com/fx/csvtest/
    │   ├── runner/
    │   │   └── CsvComponentTestRunner.java   ← JUnit 5 entry point (@SpringBootTest)
    │   ├── model/
    │   │   ├── TestCase.java                 ← CSV row → POJO
    │   │   ├── TestResult.java               ← execution outcome
    │   │   └── ExpectedOutcome.java          ← VALID / INVALID enum
    │   ├── csv/
    │   │   └── CsvTestCaseLoader.java        ← reads + parses *.csv files
    │   ├── xml/
    │   │   └── Pacs009XmlFactory.java        ← TestCase → pacs.009 XML
    │   ├── execution/
    │   │   └── TestOrchestrator.java         ← send → wait → receive → assert
    │   ├── assertion/
    │   │   ├── DomainPaymentAsserter.java    ← field-by-field comparison
    │   │   └── TimestampNormalizer.java      ← timestamp validation
    │   ├── db/
    │   │   ├── TestExecutionRecord.java      ← JPA entity for correlation
    │   │   └── TestExecutionRepository.java
    │   └── report/
    │       ├── HtmlReportGenerator.java      ← styled self-contained HTML report
    │       └── CsvReportGenerator.java       ← machine-readable CSV report
    └── resources/
        ├── application.yml
        └── test-data/
            ├── 01-happy-path-tests.csv
            ├── 02-boundary-amount-tests.csv
            ├── 03-boundary-currency-tests.csv
            ├── 04-boundary-bic-tests.csv
            ├── 05-boundary-identification-tests.csv
            ├── 06-boundary-settlement-enums-tests.csv
            ├── 07-boundary-iban-exchangerate-tests.csv
            └── 08-domain-field-mapping-tests.csv
```

---

## Reports

After `./run-tests.sh`, two report files are generated:

### HTML Report (`component-test-report.html`)

- Summary cards: total / passed / failed / pass rate / duration
- Visual progress bar (green = all pass, red = any failures)
- Per-category collapsible tables showing status, DB UUID, assertion failures, and XML preview
- Clickable XML viewer per test row

### CSV Report (`component-test-results.csv`)

Machine-readable summary for CI trend analysis:

```
testId,category,description,expectedOutcome,status,persistedStatus,persistedPaymentId,durationMs,executedAt,assertionFailures,errorMessage
HP-001,HappyPath,USD/GBP FX settlement,VALID,PASS,PROCESSED,3f4a...,,,,
```

---

## Configuration

Override defaults in `src/test/resources/application.yml` or via system properties:

| Property | Default | Description |
|----------|---------|-------------|
| `fx.component.test.test-data-dir` | `src/test/resources/test-data` | Folder scanned for CSV files |
| `fx.component.test.message-timeout-seconds` | `15` | Max seconds to wait per message |
| `fx.component.test.timestamp-freshness-minutes` | `5` | Max age of `ProcessingTimestamp` |
| `fx.component.test.report-dir` | `target/component-test-report` | Output directory for reports |

To point at a different test data folder:

```bash
mvn test -Dtest.data.dir=/path/to/my/csvs
```

---

## Database Correlation

Every dispatched message is recorded in a `test_execution` table (in the test framework's own H2 instance, separate from the AUT's DB):

```sql
SELECT test_id, tx_id, expected_outcome, state, aut_payment_id,
       result_status, failure_details, duration_ms
FROM test_execution
ORDER BY created_at;
```

This enables:
- Correlation between `txId` → AUT's UUID → domain payment
- Debugging failed tests by inspecting `aut_validation_errors`
- Duration trending across test runs

---

## CI Integration

The JUnit runner exits with a non-zero code if any test case fails, making it compatible with any CI pipeline:

```yaml
# GitHub Actions example
- name: Run FX Component Tests
  run: ./run-tests.sh
- name: Upload Report
  uses: actions/upload-artifact@v4
  with:
    name: fx-component-test-report
    path: target/component-test-report/
```
