#!/usr/bin/env bash
# ══════════════════════════════════════════════════════════════════════════
#  FX CSV Component Test Suite - One-Click Runner
#
#  What this script does:
#    1. Checks prerequisites (Java 21+, Maven 3.9+)
#    2. Runs the CSV-driven component test suite against an already-running
#       SwiftPay process using exposed embedded H2 + embedded Artemis.
#    3. Prints the path to the HTML report
#    4. Opens the report in the default browser (if available)
#
#  Requirements: Java 21+, Maven 3.9+
#  Usage:        ./run-tests.sh [--open-report]
# ══════════════════════════════════════════════════════════════════════════
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPORT_DIR="${SCRIPT_DIR}/component-test-report"
HTML_REPORT="${REPORT_DIR}/component-test-reports.html"
CSV_REPORT="${REPORT_DIR}/component-test-results.csv"

GREEN='\033[0;32m'; CYAN='\033[0;36m'; YELLOW='\033[1;33m'
RED='\033[0;31m'; BOLD='\033[1m'; NC='\033[0m'

OPEN_REPORT=false
FILE_ARG=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --open-report)
      OPEN_REPORT=true
      shift
      ;;
    --help|-h)
      echo "Usage: ./run-tests.sh [csv-filename] [--open-report]"
      echo ""
      echo "Arguments:"
      echo "  csv-filename             Run a single CSV file from src/test/resources/test-data/"
      echo "                           You can pass the exact name or a short identifier."
      echo "                           Examples: 01-happy-path-tests.csv, 02-settlement-method-tests"
      echo ""
      echo "Options:"
      echo "  --open-report            Open HTML report in browser after run"
      echo ""
      echo "Examples:"
      echo "  ./run-tests.sh                                          # run all"
      echo "  ./run-tests.sh 01-happy-path-tests.csv                 # run one file"
      echo "  ./run-tests.sh 02-settlement-method-tests --open-report"
      echo ""
      echo "Defaults:"
      echo "  Report directory: ${REPORT_DIR}"
      exit 0
      ;;
    -*)
      echo -e "${RED}ERROR: unknown option: $1${NC}"
      echo "Run ./run-tests.sh --help for usage."
      exit 1
      ;;
    *)
      if [ -z "$FILE_ARG" ]; then
        FILE_ARG="$1"
      else
        echo -e "${RED}ERROR: unexpected argument: $1${NC}"
        echo "Run ./run-tests.sh --help for usage."
        exit 1
      fi
      shift
      ;;
  esac
done

print_banner() {
  echo -e "${CYAN}"
  echo "╔══════════════════════════════════════════════════════════╗"
  echo "║   SwiftPay - CSV Component Test Suite                    ║"
  echo "║   ISO 20022 pacs.009 Boundary & Integration Tests        ║"
  echo "╚══════════════════════════════════════════════════════════╝"
  echo -e "${NC}"
}

check_prereqs() {
  echo -e "${BOLD}Checking prerequisites...${NC}"
  command -v java  >/dev/null 2>&1 || { echo -e "${RED}ERROR: java not found${NC}"; exit 1; }
  command -v mvn   >/dev/null 2>&1 || { echo -e "${RED}ERROR: mvn not found${NC}"; exit 1; }
  JAVA_VER=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
  if [ "${JAVA_VER}" -lt 21 ]; then
    echo -e "${YELLOW}WARNING: Java 21+ recommended (found ${JAVA_VER})${NC}"
  fi
  echo -e "${GREEN}  ✓ Java ${JAVA_VER}, Maven $(mvn -q --version | head -1 | cut -d' ' -f3)${NC}"
}

run_tests() {
  echo -e "${BOLD}Running CSV component test suite...${NC}"
  if [ -n "${FILE_ARG}" ]; then
    echo -e "${CYAN}  File: ${FILE_ARG}${NC}"
  else
    echo -e "${CYAN}  Mode: all CSV files in test-data/${NC}"
  fi
  echo -e "${CYAN}  Expected: fx-payment-processor is running in another terminal${NC}"
  echo ""
  cd "$SCRIPT_DIR"

  TEST_DATA_DIR="${SCRIPT_DIR}/src/test/resources/test-data"
  if [ -n "${FILE_ARG}" ]; then
    # Resolve the requested CSV file from the test-data directory
    REQUESTED="$(basename "$FILE_ARG")"
    CANDIDATE=""

    if [ -f "${TEST_DATA_DIR}/${REQUESTED}" ]; then
      CANDIDATE="${TEST_DATA_DIR}/${REQUESTED}"
    elif [ -f "${TEST_DATA_DIR}/${REQUESTED}.csv" ]; then
      CANDIDATE="${TEST_DATA_DIR}/${REQUESTED}.csv"
    else
      found=0
      for f in "${TEST_DATA_DIR}"/*"${REQUESTED}"*.csv; do
        if [ -f "$f" ]; then
          if [ "$found" -eq 0 ]; then
            CANDIDATE="$f"
            found=1
          else
            found=2
            break
          fi
        fi
      done
      if [ "$found" -eq 2 ]; then
        echo -e "${RED}ERROR: multiple matching files found for '${REQUESTED}':${NC}"
        for f in "${TEST_DATA_DIR}"/*"${REQUESTED}"*.csv; do
          [ -f "$f" ] && echo "  $(basename "$f")"
        done
        exit 1
      fi
    fi

    if [ -z "${CANDIDATE}" ]; then
      echo -e "${RED}ERROR: No matching CSV file found for '${REQUESTED}' in ${TEST_DATA_DIR}${NC}"
      exit 1
    fi

    TEMP_DIR="${SCRIPT_DIR}/temp-test-data-${REQUESTED%.*}"
    rm -rf "$TEMP_DIR"
    mkdir -p "$TEMP_DIR"
    cp "$CANDIDATE" "$TEMP_DIR/"
    TEST_DATA_DIR="$TEMP_DIR"
    FILE_ARG="$(basename "$CANDIDATE")"
  fi

  # Run Maven, capture exit code without 'set -e' killing us
  set +e
  mvn clean test \
    -Dspring.profiles.active=default \
    -Dreport.dir="${REPORT_DIR}" \
    -Dtest.data.dir="${TEST_DATA_DIR}" \
    2>&1
  MAVEN_EXIT=$?
  set -e
  return $MAVEN_EXIT
}

print_results() {
  local exit_code=$1
  echo ""
  echo -e "${BOLD}══════════════════════════════════════════════════════════${NC}"
  if [ "$exit_code" -eq 0 ]; then
    echo -e "${GREEN}${BOLD}  ✓ ALL TESTS PASSED${NC}"
  else
    echo -e "${RED}${BOLD}  ✗ SOME TESTS FAILED (exit code: ${exit_code})${NC}"
  fi
  echo ""
  if [ -f "$HTML_REPORT" ]; then
    echo -e "  HTML Report → ${CYAN}${HTML_REPORT}${NC}"
  fi
  if [ -f "$CSV_REPORT" ]; then
    echo -e "  CSV  Report → ${CYAN}${CSV_REPORT}${NC}"
  fi
  echo -e "${BOLD}══════════════════════════════════════════════════════════${NC}"
}

open_report() {
  if [ -f "$HTML_REPORT" ]; then
    echo -e "\nOpening report in browser..."
    if command -v xdg-open >/dev/null 2>&1; then xdg-open "$HTML_REPORT"
    elif command -v open >/dev/null 2>&1;     then open "$HTML_REPORT"
    else echo "Open manually: file://${HTML_REPORT}"
    fi
  fi
}

# ── Main ──────────────────────────────────────────────────────────────────
print_banner
check_prereqs
run_tests
TESTS_EXIT=$?
print_results $TESTS_EXIT
[ "$OPEN_REPORT" = true ] && open_report
exit $TESTS_EXIT
