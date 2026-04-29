#!/usr/bin/env bash
# ══════════════════════════════════════════════════════════════════════════
#  FX CSV Component Test Suite – One-Click Runner
#
#  What this script does:
#    1. Checks prerequisites (Java 21+, Maven 3.9+)
#    2. Installs the AUT (fx-payment-processor) into the local Maven repo
#    3. Runs the full CSV-driven component test suite
#    4. Prints the path to the HTML report
#    5. Opens the report in the default browser (if available)
#
#  Requirements: Java 21+, Maven 3.9+
#  Usage:        ./run-tests.sh [--skip-install]  [--open-report]
# ══════════════════════════════════════════════════════════════════════════
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AUT_DIR="${SCRIPT_DIR}/../fx-payment-processor"
REPORT_DIR="${SCRIPT_DIR}/target/component-test-report"
HTML_REPORT="${REPORT_DIR}/component-test-report.html"
CSV_REPORT="${REPORT_DIR}/component-test-results.csv"

GREEN='\033[0;32m'; CYAN='\033[0;36m'; YELLOW='\033[1;33m'
RED='\033[0;31m'; BOLD='\033[1m'; NC='\033[0m'

SKIP_INSTALL=false
OPEN_REPORT=false
for arg in "$@"; do
  case $arg in --skip-install) SKIP_INSTALL=true ;; --open-report) OPEN_REPORT=true ;; esac
done

print_banner() {
  echo -e "${CYAN}"
  echo "╔══════════════════════════════════════════════════════════╗"
  echo "║   FX Payment Processor – CSV Component Test Suite        ║"
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

install_aut() {
  if [ "$SKIP_INSTALL" = true ]; then
    echo -e "${YELLOW}Skipping AUT install (--skip-install flag set)${NC}"
    return
  fi
  echo -e "${BOLD}Installing AUT (fx-payment-processor) into local Maven repo...${NC}"
  if [ ! -d "$AUT_DIR" ]; then
    echo -e "${RED}ERROR: AUT directory not found: ${AUT_DIR}${NC}"
    echo "  Make sure fx-payment-processor is at ../fx-payment-processor relative to this project."
    exit 1
  fi
  (cd "$AUT_DIR" && mvn install -DskipTests -q)
  echo -e "${GREEN}  ✓ AUT installed successfully${NC}"
}

run_tests() {
  echo -e "${BOLD}Running CSV component test suite...${NC}"
  echo ""
  cd "$SCRIPT_DIR"
  # Run Maven, capture exit code without 'set -e' killing us
  set +e
  mvn test \
    -Dreport.dir="${REPORT_DIR}" \
    -Dtest.data.dir="${SCRIPT_DIR}/src/test/resources/test-data" \
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
install_aut
run_tests
TESTS_EXIT=$?
print_results $TESTS_EXIT
[ "$OPEN_REPORT" = true ] && open_report
exit $TESTS_EXIT
