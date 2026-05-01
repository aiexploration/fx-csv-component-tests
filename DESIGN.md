# FX CSV Component Test Suite — Design Document

## 1. Overview

This project is a **CSV-driven component test framework** for the `fx-payment-processor`
application. Its purpose is to validate the FX payment processor end-to-end without writing
Java code for each test scenario. Instead, every test case is one row in a CSV file.

The suite runs inside the same JVM as the Application Under Test (AUT). Both the test
framework and the AUT share a single Spring `ApplicationContext`, started by
`@SpringBootTest`. The AUT's internal HTTP/DB/messaging infrastructure is embedded and
exercised for real.

---

## 2. Repository Structure

```
fx-csv-component-tests/
├── pom.xml                              Maven build descriptor
├── lombok.config                        Lombok settings (copyable annotations)
├── run-tests.sh                         One-click runner script
├── src/test/
│   ├── java/com/fx/csvtest/
│   │   ├── config/
│   │   │   └── CsvTestSuiteConfig.java  Spring config: component scan + JPA scan
│   │   ├── runner/
│   │   │   └── CsvComponentTestRunner.java   JUnit 5 test class (entry point)
│   │   ├── csv/
│   │   │   └── CsvTestCaseLoader.java        CSV → TestCase parser
│   │   ├── xml/
│   │   │   └── Pacs009XmlFactory.java         TestCase → pacs.009 XML builder
│   │   ├── execution/
│   │   │   └── TestOrchestrator.java          Send → Wait → Assert → Record
│   │   ├── assertion/
│   │   │   ├── DomainPaymentAsserter.java     Field-by-field assertion engine
│   │   │   └── TimestampNormalizer.java       ISO-8601 timestamp validation
│   │   ├── model/
│   │   │   ├── TestCase.java                  Data class for one CSV row
│   │   │   ├── TestResult.java                Outcome of executing one test case
│   │   │   └── ExpectedOutcome.java           Enum: VALID | INVALID
│   │   ├── db/
│   │   │   ├── TestExecutionRecord.java        JPA entity for test correlation
│   │   │   └── TestExecutionRepository.java    Spring Data repository
│   │   └── report/
│   │       ├── HtmlReportGenerator.java        Self-contained HTML report
│   │       └── CsvReportGenerator.java         Machine-readable CSV report
│   └── resources/
│       ├── application.yml                     Spring config overrides (H2, etc.)
│       └── test-data/                          CSV test case files
│           ├── 01-happy-path-tests.csv
│           ├── 02-boundary-amount-tests.csv
│           ├── 03-boundary-currency-tests.csv
│           ├── 04-boundary-bic-tests.csv
│           ├── 05-boundary-identification-tests.csv
│           ├── 06-boundary-settlement-enums-tests.csv
│           ├── 07-boundary-iban-exchangerate-tests.csv
│           └── 08-domain-field-mapping-tests.csv
```

---

## 3. Application Under Test (AUT)

The AUT lives in the sibling project `fx-payment-processor`. It is a Spring Boot service
that processes ISO 20022 pacs.009 (Financial Institution Credit Transfer) messages.

### 3.1 AUT Processing Pipeline

```
RabbitMQ                  AUT (same JVM in tests)               RabbitMQ
fx.pacs009.inbound  →  Pacs009MessageListener                         
                              │                                        
                    ┌─────────▼─────────────┐                        
                    │ PaymentOrchestration   │                        
                    │ Service                │                        
                    │  1. XSD Validate       │                        
                    │  2. Persist (VALIDATED)│                        
                    │  3. Transform          │                        
                    │  4. Route              │──────► fx.payment.valid  
                    │  5. Update (PROCESSED) │                        
                    └─────────┬─────────────┘                        
                              │ on failure                           
                              └──────────────────► fx.payment.invalid 
```

### 3.2 AUT Key Classes

| Class | Role |
|---|---|
| `FxPaymentProcessorApplication` | Spring Boot entry point (`@SpringBootApplication`) |
| `Pacs009MessageListener` | `@RabbitListener` on `fx.pacs009.inbound`; receives raw XML as `String` |
| `PaymentOrchestrationService` | Coordinates the 5-step processing pipeline |
| `Pacs009ValidationService` | XSD-validates against `pacs.009.001.08.xsd`, then JAXB-unmarshals |
| `PaymentPersistenceService` | Saves `PaymentMessage` entity (JPA/H2 in tests) |
| `PaymentTransformationService` | Maps `Pacs009Document` → `DomainPayment` + serialises to XML |
| `PaymentRoutingService` | Publishes domain XML to `fx.payment.valid` or raw XML to `fx.payment.invalid` |
| `RabbitConfig` | Declares exchange, queues, bindings; creates `RabbitTemplate` |
| `EmbeddedInspectionConfig` | Opens H2 TCP/web console ports (disabled in test via property) |

### 3.3 AUT Data Model

**`PaymentMessage`** (JPA entity, table `payment_message`)

| Column | Type | Description |
|---|---|---|
| `id` | `UUID` (PK, auto) | Correlation key propagated to all downstream |
| `message_id` | `VARCHAR(35)` | pacs.009 `MsgId` |
| `transaction_id` | `VARCHAR(35)` | pacs.009 `TxId` (correlation key used by tests) |
| `end_to_end_id` | `VARCHAR(35)` | pacs.009 `EndToEndId` |
| `uetr` | `VARCHAR(36)` | UUID v4 end-to-end reference |
| `settlement_amount` | `DECIMAL(18,5)` | |
| `settlement_currency` | `CHAR(3)` | ISO 4217 |
| `settlement_date` | `DATE` | |
| `exchange_rate` | `DECIMAL(18,10)` | |
| `debtor_bic` | `VARCHAR(11)` | |
| `creditor_bic` | `VARCHAR(11)` | |
| `status` | `ENUM` | `VALIDATED → PROCESSED` or `INVALID` |
| `validation_errors` | `VARCHAR(2000)` | Error detail for INVALID messages |
| `raw_xml` | `TEXT` | Full original XML (audit) |
| `created_at` / `updated_at` | `TIMESTAMP` | |

**`PaymentStatus`** enum: `VALIDATED`, `PROCESSED`, `INVALID`

**`DomainPayment`** (JAXB-annotated, serialised to XML for `fx.payment.valid`)

Carries `PaymentId` (UUID), identity fields, settlement fields, debtor/creditor BICs,
charge bearer, purpose code, remittance info, and processing timestamp.

---

## 4. Test Framework Architecture

### 4.1 Spring Context Composition

The test bootstraps **two configuration classes** together:

```java
@SpringBootTest(classes = {
    FxPaymentProcessorApplication.class,   // the full AUT
    CsvTestSuiteConfig.class               // the test framework beans
})
```

`CsvTestSuiteConfig` does three things:

```java
@Configuration
@ComponentScan("com.fx.csvtest")           // discovers all test-framework @Components
@EnableJpaRepositories(basePackages = {"com.fx.payment", "com.fx.csvtest"})
@EntityScan(basePackages = {"com.fx.payment", "com.fx.csvtest"})
public class CsvTestSuiteConfig { }
```

The `@EnableJpaRepositories` / `@EntityScan` are required because the AUT's
`@SpringBootApplication` only auto-scans the `com.fx.payment` package; the test
framework's `TestExecutionRepository` and `TestExecutionRecord` live in
`com.fx.csvtest` and would otherwise not be found.

### 4.2 Infrastructure Overrides (`application.yml`)

The test's `application.yml` overrides the AUT's production settings:

| Setting | Production (AUT) | Test override |
|---|---|---|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/fxpayments` | `jdbc:h2:mem:testdb` |
| `spring.datasource.driver-class-name` | `org.postgresql.Driver` | `org.h2.Driver` |
| `spring.jpa.hibernate.ddl-auto` | `update` | `create-drop` |
| `spring.jpa.properties.hibernate.dialect` | `PostgreSQLDialect` | `H2Dialect` |
| `fx.inspection.h2.enabled` | `true` | `false` (disables TCP/web H2 consoles) |
| `spring.rabbitmq.template.receive-timeout` | (default) | `5000` ms |

Both the AUT's `PaymentMessage` table and the test framework's `test_execution` table are
created in the same in-memory H2 database at test startup (`create-drop`).

RabbitMQ must be running externally (Docker). The test uses the same broker as the AUT.

---

## 5. Component-by-Component Description

### 5.1 `CsvComponentTestRunner` — JUnit 5 Entry Point

Four ordered `@Test` methods execute in sequence:

```
@Order(1)  loadTestCases()     — CSV → List<TestCase>
@Order(2)  executeAllTestCases() — for each TestCase: build XML → send → assert
@Order(3)  generateReports()   — write HTML + CSV reports
@Order(4)  assertSuitePassed() — fail JUnit if any test case failed
```

The `@Order(4)` step is the only one that can fail the JUnit build. The earlier steps
accumulate results without throwing, so the report is always generated regardless of
test failures.

### 5.2 `CsvTestCaseLoader` — CSV Parser

- Scans `${fx.component.test.test-data-dir}` for `*.csv` files (alphabetical order)
- Uses **OpenCSV RFC 4180 parser** (handles quoted fields with commas)
- First row is a header; rows starting with `#` are comments
- Maps each row to a `TestCase` via column name lookup (order-independent headers)
- Required columns: `testId`, `txId`, `expectedOutcome`
- All other columns are optional (blank = absent = skip assertion)

### 5.3 `Pacs009XmlFactory` — XML Builder

Constructs a pacs.009.001.08 XML string from a `TestCase`. Key behaviours:

- **Auto-fills** `MsgId` (`MSG-{txId}`) and `CreDtTm` (current timestamp) if blank
- **`omitElements`** column: comma-separated element names to exclude from XML
  (used to trigger XSD validation failures, e.g. `omitElements=TxId`)
- **`rawXmlOverrides`** column: `ElementName=<rawXmlSnippet>||...` to inject
  deliberately malformed content (e.g. negative amounts, invalid currency codes)

Element order in generated XML matches the pacs.009.001.08 XSD sequence:
`GrpHdr → PmtId → IntrBkSttlmAmt → IntrBkSttlmDt → XchgRate → ChrgBr →
Dbtr → DbtrAcct → DbtrAgt → CdtrAgt → Cdtr → CdtrAcct → Purp → RmtInf`

### 5.4 `TestOrchestrator` — Execute One Test Case

For each test case:

1. **Persist** a `TestExecutionRecord` (state = `SENT`) in the framework's H2 DB
2. **Send** the XML to `fx.pacs009.inbound` using `rabbitTemplate.send()` with
   `CONTENT_TYPE_XML` (raw bytes — bypasses Jackson converter)
3. **Wait** (Awaitility, up to 15 s) for AUT to write a DB record:
   - VALID path: polls `PaymentMessageRepository.findByTransactionId()` until
     `status == PROCESSED`
   - INVALID path: polls `PaymentMessageRepository.findByStatus(INVALID)` until
     count increases
4. **Receive** domain payment from `fx.payment.valid` (VALID path) or raw XML
   from `fx.payment.invalid` (INVALID path)
5. **Assert** via `DomainPaymentAsserter`
6. **Update** the `TestExecutionRecord` with outcome and duration
7. **Return** a `TestResult`

### 5.5 `DomainPaymentAsserter` — Field Assertions

Compares every non-blank expected column from the CSV row against the received
`DomainPayment`. Key design decisions:

- **Sparse assertions**: blank expected columns are skipped ("don't care")
- **Soft failures**: all mismatches are accumulated in `TestResult.assertionFailures`
  (no early throw), so one test case reports all failures at once
- **Decimal comparison**: uses `BigDecimal.compareTo()` (not `equals()`) so
  `1.00` == `1` == `1.0`
- **Timestamp**: validates ISO-8601 format and freshness (≤ 5 minutes old) rather
  than exact match, because the AUT generates the timestamp at runtime
- **UUID consistency**: asserts that `domain.PaymentId` matches the UUID in the
  AUT's `PaymentMessage` table

### 5.6 `TimestampNormalizer`

Strips sub-second precision (`2024-04-15T10:30:00.123` → `2024-04-15T10:30:00`) to
normalise comparison. Used by `DomainPaymentAsserter.assertFresh()`.

### 5.7 `TestExecutionRecord` — Test Correlation DB

JPA entity persisted in the test framework's own H2 table (`test_execution`). Acts
as an audit log correlating:

- The sent `txId` / `msgId`
- The AUT's assigned `autPaymentId` (UUID)
- The received domain XML or invalid XML
- The outcome (`PASS` / `FAIL` / `ERROR` / `TIMEOUT`)
- Duration in milliseconds

This table is separate from the AUT's `payment_message` table but lives in the
same in-memory H2 database.

### 5.8 `HtmlReportGenerator` — Self-Contained HTML Report

Generates a single-file, dependency-free HTML report at
`target/component-test-report/component-test-report.html`. Features:

- Summary cards (passed / failed / errors / total / pass rate / duration)
- Progress bar (green if all pass, red if any fail)
- Per-category sections with badge counts
- Per-row expandable XML viewer (toggle button shows sent/received XML)
- Inline CSS and JavaScript — no external dependencies

### 5.9 `CsvReportGenerator` — Machine-Readable CSV Report

Writes `target/component-test-report/component-test-results.csv` for CI integration
and trend analysis. Columns: testId, category, description, expectedOutcome, status,
persistedStatus, persistedPaymentId, durationMs, executedAt, assertionFailures,
errorMessage.

---

## 6. CSV Test Case Format

### 6.1 Column Reference

| Column | Required | Description |
|---|---|---|
| `testId` | **Yes** | Unique ID (e.g. `HP-001`) |
| `description` | No | Short description for the report |
| `category` | No | Group name (default: `General`) |
| `expectedOutcome` | **Yes** | `VALID` or `INVALID` |
| `expectedErrorContains` | No | Substring expected in `validationErrors` (INVALID only) |
| `msgId` | No | pacs.009 `MsgId` (auto-generated if blank) |
| `creDtTm` | No | `CreDtTm` (auto-generated if blank) |
| `nbOfTxs` | No | `NbOfTxs` (default: `1`) |
| `settlementMethod` | No | `CLRG` \| `COVE` \| `GROS` \| `INDA` (default: `GROS`) |
| `instrId` | No | Optional `InstrId` |
| `endToEndId` | No | `EndToEndId` (default: `E2E-{txId}`) |
| `txId` | **Yes** | Correlation key; must match what AUT stores |
| `uetr` | No | UUID v4 |
| `amount` | No | Settlement amount (default: `1000000.00`) |
| `currency` | No | ISO 4217 3-letter code (default: `USD`) |
| `settlementDate` | No | `YYYY-MM-DD` (default: `2024-04-17`) |
| `exchangeRate` | No | Decimal |
| `chargeBearer` | No | `CRED` \| `DEBT` \| `SHAR` \| `SLEV` (default: `SHAR`) |
| `debtorBic` | No | BICFI 8 or 11 chars |
| `debtorName` | No | |
| `debtorIban` | No | |
| `debtorAgentBic` | No | Default: `BOFAUS3N` |
| `creditorAgentBic` | No | Default: `CHASUS33` |
| `creditorBic` | No | |
| `creditorName` | No | |
| `creditorIban` | No | |
| `purposeCode` | No | 4-char code e.g. `CORT`, `TREA` |
| `remittanceInfo` | No | Max 140 chars |
| `omitElements` | No | Comma-separated element names to exclude from XML |
| `rawXmlOverrides` | No | `Elem=<raw/>||Elem2=<raw/>` injection format |
| `expectedPaymentStatus` | No | Default: `PROCESSED` |
| `expectedSettlementAmount` | No | Asserted as `BigDecimal` |
| `expectedSettlementCurrency` | No | |
| `expectedSettlementDate` | No | |
| `expectedSettlementMethod` | No | |
| `expectedExchangeRate` | No | Asserted as `BigDecimal` |
| `expectedDebtorBic` | No | |
| `expectedCreditorBic` | No | |
| `expectedDebtorIban` | No | |
| `expectedCreditorIban` | No | |
| `expectedDebtorName` | No | |
| `expectedCreditorName` | No | |
| `expectedDebtorAgentBic` | No | |
| `expectedCreditorAgentBic` | No | |
| `expectedChargeBearer` | No | |
| `expectedPurposeCode` | No | |
| `expectedRemittanceInfo` | No | |
| `expectedUetr` | No | |
| `expectedEndToEndId` | No | |

### 6.2 Example Row

```csv
testId,category,description,expectedOutcome,txId,amount,currency,debtorBic,creditorBic,expectedSettlementAmount,expectedSettlementCurrency
HP-001,HappyPath,USD/GBP FX settlement,VALID,HP-TXN-001,1250000.00,USD,BARCGB22,JPMSGB2L,1250000.00,USD
```

### 6.3 INVALID Test Patterns

```csv
# Omit a required element to trigger XSD failure
BIC-INVALID-001,InvalidBIC,Missing debtor BIC,INVALID,IV-TXN-001,,,,...,,,omitElements=Dbtr,...

# Inject malformed XML
AMT-INVALID-001,InvalidAmt,Negative amount,INVALID,IV-TXN-002,,,...,rawXmlOverrides=IntrBkSttlmAmt=<IntrBkSttlmAmt Ccy='USD'>-100</IntrBkSttlmAmt>,...
```

---

## 7. Message Flow (Detailed)

```
Test Thread                   RabbitMQ Broker           AUT Listener Thread
────────────                  ───────────────           ───────────────────
build XML
  │
  ├─send(XML bytes)──────────► fx.pacs009.inbound ──────► onMessage(rawXml)
  │                                                            │
  │                                                   validateAndUnmarshal()
  │                                                            │ fail?
  │                                                     ┌──── ├────► persistInvalid()
  │                                                     │     │      updateStatus(INVALID)
  │                                                     │     └──────────────────────────► fx.payment.invalid
  │                                                     │
  │                                                     │ pass
  │                                                     └──► persistValid()    (status=VALIDATED)
  │                                                              │
  │                                                         toDomainPayment()
  │                                                              │
  │                                                         routeValid(domainXml)──────────► fx.payment.valid
  │                                                              │
  │                                                         updateStatus(PROCESSED)
  │
  ├─poll DB (Awaitility)──────────────────────────────────────► findByTransactionId(txId)
  │    until status=PROCESSED                                    == PROCESSED? ──► proceed
  │
  ├─receive(fx.payment.valid)◄──────────────────────────────── drain until txId match
  │
  ├─assertAll(testCase, domain)
  │    PaymentId (UUID format)
  │    OriginalMessageId (non-blank)
  │    TransactionId == txId
  │    PaymentStatus == PROCESSED
  │    ProcessingTimestamp (fresh ISO-8601)
  │    [optional fields if set in CSV]
  │
  └─persist TestExecutionRecord (outcome, duration)
```

---

## 8. Configuration Properties

All properties have defaults; override via system properties (`-D`) or `application.yml`.

| Property | Default | Description |
|---|---|---|
| `fx.component.test.test-data-dir` | `src/test/resources/test-data` | Directory scanned for `*.csv` files |
| `fx.component.test.message-timeout-seconds` | `15` | Max wait per test case for AUT to process |
| `fx.component.test.timestamp-freshness-minutes` | `5` | Max age for `ProcessingTimestamp` |
| `fx.component.test.report-dir` | `target/component-test-report` | Output directory for reports |
| `fx.inspection.h2.enabled` | `true` | Enable H2 TCP console (set `false` in tests) |
| `fx.inspection.h2.web.enabled` | `true` | Enable H2 web console (set `false` in tests) |

---

## 9. Dependencies

| Dependency | Purpose |
|---|---|
| `spring-boot-starter-test` | JUnit 5, AssertJ, Mockito |
| `spring-boot-starter-amqp` | RabbitTemplate, `@RabbitListener` |
| `spring-boot-starter-data-jpa` | JPA for `TestExecutionRepository` |
| `com.h2database:h2` | In-memory DB replaces AUT's PostgreSQL |
| `com.opencsv:opencsv` | RFC 4180-compliant CSV parsing |
| `org.awaitility:awaitility` | Polling AUT's DB until expected state |
| `org.projectlombok:lombok` | `@Builder`, `@Data`, `@Slf4j` on model/service classes |
| `jakarta.xml.bind:jakarta.xml.bind-api` + `jaxb-impl` | JAXB for `DomainPayment` unmarshalling |
| `com.fx:fx-payment-processor` (AUT) | Provides all AUT classes + RabbitConfig constants |

---

## 10. Known Issues and Limitations

### 10.1 Queue Isolation Between Runs

RabbitMQ queues persist between test runs. If a previous run left messages on
`fx.pacs009.inbound` (e.g. due to a crash or timeout), they will be processed by the
AUT before the current run's messages, causing timeout failures. **Fix**: drain all
three queues at suite startup (`@BeforeAll`).

### 10.2 txId Re-use Across Runs

H2 is created fresh each run (`create-drop`), but RabbitMQ is not. If the same `txId`
is sent twice (across different runs), the AUT will process both but the second
`findByTransactionId()` query may match the first run's DB record if H2 is not reset.
**Fix**: use unique `txId` values (e.g. timestamp-prefixed) or drain DB between runs.

### 10.3 INVALID Test Correlation by Count

For INVALID tests, `TestOrchestrator` waits for the total count of INVALID records in
the DB to increase, then picks the most recently created one. This is fragile when
tests run concurrently or when multiple INVALID messages are in-flight.

### 10.4 No Parallel Execution

Test cases run sequentially (single thread) by design, since each test waits for the
AUT's async processing to complete. Parallel execution would require per-test queue
isolation.

### 10.5 Static `allTestCases`

`CsvComponentTestRunner.allTestCases` is a `static` field shared across all JUnit
instances. JUnit 5 may create multiple test instances; the `@TestMethodOrder` +
`@Order` annotations ensure `loadTestCases()` always runs before
`executeAllTestCases()`, but the shared static state is a code smell.

---

## 11. Running the Tests

### Prerequisites

1. Java 21+, Maven 3.9+
2. RabbitMQ running on `localhost:5672` (default credentials `guest`/`guest`)
3. AUT jar installed to local Maven repo:
   ```bash
   cd ../fx-payment-processor && mvn install -DskipTests
   ```

### Execute

```bash
./run-tests.sh                  # install AUT + run + print report path
./run-tests.sh --open-report    # same + open HTML report in browser
./run-tests.sh --skip-install   # skip AUT install (already done)

mvn test                        # direct Maven invocation
```

### Reports

```
target/component-test-report/
├── component-test-report.html   # self-contained HTML (open in browser)
└── component-test-results.csv   # machine-readable summary
```
