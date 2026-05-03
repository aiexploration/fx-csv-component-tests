# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Project Is

A CSV-driven component test framework for the **FX Payment Processor** (`fx-payment-processor`). Tests are defined entirely in CSV files — no Java changes required to add new scenarios. The framework builds ISO 20022 pacs.009 XML from each CSV row, sends it to the processor via RabbitMQ, and asserts the output from the database and outbound queues.

This project does not run in isolation. It requires the `fx-payment-processor` to be running as the Application Under Test (AUT).

## Running Tests

```bash
# Terminal 1 — start the AUT (fx-payment-processor with embedded H2 + Artemis)
./start-component.sh

# Terminal 2 — run all CSV tests and open HTML report
./run-tests.sh --open-report

# Run a single CSV file (no .csv extension needed)
./run-tests.sh 02-settlement-method-tests

# Inspect queues interactively (read-only)
./inspect-queues.sh
```

Makefile equivalents:
```bash
make start-component   # start AUT
make run               # run tests
make run-open          # run + open HTML report
make inspect-queues    # browse message queues
make clean             # remove build artifacts
```

Reports are generated to `target/component-test-report/`:
- `component-test-report.html` — styled HTML with per-category collapsible tables, pass/fail cards, XML previews
- `component-test-results.csv` — machine-readable for CI trend tracking

## Architecture

```
*.csv (test-data/)
    │
    ▼
CsvTestCaseLoader          → parses CSV rows into TestCase POJOs
    │
    ▼
Pacs009XmlFactory          → builds pacs.009 XML from TestCase fields
    │                         (supports omitElements and rawXmlOverrides)
    ▼
RabbitMQ: fx.pacs009.inbound  ──→  [fx-payment-processor AUT]
                                          │              │
                                   fx.payment.valid   fx.payment.invalid
                                          │
                                          ▼
                               DomainPaymentAsserter  ← polls payment_message table
                                          │
                                          ▼
                               HTML + CSV report  →  JUnit pass/fail
```

### Key Source Packages (`src/test/java/com/fx/csvtest/`)

| Package | Role |
|---|---|
| `runner/` | `CsvComponentTestRunner` — JUnit 5 `@SpringBootTest` entry point; orchestrates load → execute → report → assert |
| `csv/` | `CsvTestCaseLoader` — reads `*.csv` files alphabetically, skips comment rows (`#`) |
| `xml/` | `Pacs009XmlFactory` — CSV row → ISO 20022 pacs.009 XML via JAXB |
| `execution/` | `TestOrchestrator` — send → wait → receive → assert pipeline |
| `assertion/` | `DomainPaymentAsserter` — field-by-field comparison; `TimestampNormalizer` |
| `db/` | `AutPaymentJdbcClient` — polls AUT `payment_message` table by `txId` |
| `report/` | `HtmlReportGenerator`, `CsvReportGenerator` |
| `tools/` | `RabbitQueueInspector` — read-only queue browser |
| `model/` | `TestCase`, `TestResult`, `ExpectedOutcome` |

Configuration: `src/test/resources/application.yml`
- `fx.component.test.test-data-dir` — folder containing CSV files
- `fx.component.test.message-timeout-seconds` — per-message timeout (default 15s)
- `fx.component.test.timestamp-freshness-minutes` — max timestamp age (default 5min)

## CSV Test Format

CSV files live in `src/test/resources/test-data/` and are loaded alphabetically. Comment rows starting with `#` are skipped.

**Input columns** (what to send):

| Column | Required | Notes |
|---|---|---|
| `testId` | Yes | Unique ID, e.g. `HP-001` |
| `category` | Yes | Groups tests in the report |
| `description` | Yes | Human-readable test description |
| `expectedOutcome` | Yes | `VALID` or `INVALID` |
| `msgId`, `txId`, `endToEndId`, `instrId` | Yes | pacs.009 identifiers |
| `uetr` | No | UUID v4; omitted when blank |
| `settlementMethod` | Yes | `GROS`, `CLRG`, `COVE`, `INDA` |
| `amount`, `currency`, `settlementDate` | Yes | ISO 4217 currency |
| `exchangeRate` | Yes | Decimal, e.g. `1.2650000000` |
| `chargeBearer` | Yes | `SHAR`, `CRED`, `DEBT`, `SLEV` |
| `debtorBic`, `debtorName`, `debtorIban` | Yes/No | IBAN optional |
| `debtorAgentBic`, `creditorAgentBic` | Yes | Intermediary BICs |
| `creditorBic`, `creditorName`, `creditorIban` | Yes/No | |
| `purposeCode` | No | ISO purpose code, e.g. `CORT`, `TREA` |
| `remittanceInfo` | No | Free text |
| `omitElements` | No | Comma-separated XPath elements to remove from XML |
| `rawXmlOverrides` | No | Raw XML snippet to inject (invalid-format tests) |

**Expected assertion columns** (what to verify):

`expectedPaymentStatus`, `expectedSettlementAmount`, `expectedSettlementCurrency`, `expectedSettlementDate`, `expectedSettlementMethod`, `expectedExchangeRate`, `expectedDebtorBic`, `expectedCreditorBic`, `expectedDebtorIban`, `expectedCreditorIban`, `expectedDebtorName`, `expectedCreditorName`, `expectedDebtorAgentBic`, `expectedCreditorAgentBic`, `expectedChargeBearer`, `expectedPurposeCode`, `expectedRemittanceInfo`, `expectedUetr`, `expectedEndToEndId`, `expectedErrorContains`

Blank assertion columns are skipped. `expectedErrorContains` is for `INVALID` outcomes.

**Existing test files:**

| File | Category |
|---|---|
| `01-happy-path-tests.csv` | `HappyPath` — USD/GBP GROS baseline |
| `02-settlement-method-tests.csv` | `SettlementMethod` — CLRG, COVE, INDA |
| `03-currency-pair-tests.csv` | `CurrencyPair` — multi-currency pairs |
| `04-charge-bearer-tests.csv` | `ChargeBearer` — CRED, DEBT, SLEV |
| `05-optional-fields-tests.csv` | `OptionalFields` — absent IBAN, UETR, purpose |
| `06-invalid-missing-fields-tests.csv` | `InvalidMissingFields` — missing required elements |
| `07-invalid-format-tests.csv` | `InvalidFormat` — XSD violations |

## Related Project: fx-payment-processor

Location: `../fx-payment-processor/fx-payment-processor`

The AUT processes inbound `fx.pacs009.inbound` messages through a 5-step pipeline:
1. XSD validation against `pacs.009.001.08.xsd`
2. JAXB unmarshal to object model
3. Persist `PaymentMessage` entity (PostgreSQL/H2)
4. Transform to `DomainPayment`
5. Route to `fx.payment.valid` or `fx.payment.invalid`

The AUT's `payment_message` table is queried directly by this test harness using `txId` as the correlation key.

**AUT shell scripts:**
```bash
./start.sh            # embedded mode (Artemis + H2) — used by start-component.sh
./start-postgres.sh   # Docker mode (RabbitMQ + PostgreSQL)
make test             # run AUT's own unit/integration tests
make build            # build fat JAR
```

## Message Queue Topology

| Queue | Direction | Contents |
|---|---|---|
| `fx.pacs009.inbound` | Test harness → AUT | Raw ISO 20022 pacs.009 XML |
| `fx.payment.valid` | AUT → Test harness | Domain payment XML |
| `fx.payment.invalid` | AUT → Test harness | Rejected XML + validation errors |
