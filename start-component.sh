#!/usr/bin/env bash
# Start SwiftPay as a separate process for CSV component testing.
#
# This uses SwiftPay's embedded profile and exposes its in-memory H2 database
# and embedded Artemis broker over local TCP ports while the JVM is running.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SWIFTPAY_DIR="${SWIFTPAY_DIR:-${SCRIPT_DIR}/../swiftpay}"

GREEN='\033[0;32m'; CYAN='\033[0;36m'; RED='\033[0;31m'; BOLD='\033[1m'; NC='\033[0m'

while [ "$#" -gt 0 ]; do
  case "$1" in
    --swiftpay-dir)
      [ "$#" -ge 2 ] || { echo -e "${RED}ERROR: --swiftpay-dir requires a path${NC}"; exit 1; }
      SWIFTPAY_DIR="$2"
      shift 2
      ;;
    --swiftpay-dir=*)
      SWIFTPAY_DIR="${1#*=}"
      shift
      ;;
    --help|-h)
      echo "Usage: ./start-component.sh [--swiftpay-dir PATH]"
      echo ""
      echo "Starts SwiftPay with embedded H2 + embedded Artemis exposed over TCP."
      echo "Keep this terminal open while running ./run-tests.sh in another terminal."
      exit 0
      ;;
    *)
      echo -e "${RED}ERROR: unknown argument: $1${NC}"
      echo "Run ./start-component.sh --help for usage."
      exit 1
      ;;
  esac
done

SWIFTPAY_DIR="$(cd "$SWIFTPAY_DIR" 2>/dev/null && pwd || true)"

if [ -z "$SWIFTPAY_DIR" ] || [ ! -d "$SWIFTPAY_DIR" ]; then
  echo -e "${RED}ERROR: SwiftPay directory not found.${NC}"
  echo "  Expected: ${SCRIPT_DIR}/../swiftpay"
  echo "  Or pass:  ./start-component.sh --swiftpay-dir /path/to/swiftpay"
  exit 1
fi

if [ ! -f "$SWIFTPAY_DIR/start.sh" ]; then
  echo -e "${RED}ERROR: no start.sh found in SwiftPay directory: ${SWIFTPAY_DIR}${NC}"
  exit 1
fi

echo -e "${CYAN}"
echo "╔══════════════════════════════════════════════════════════╗"
echo "║   Starting SwiftPay Component                            ║"
echo "║   Separate process + exposed embedded H2/Artemis         ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo -e "${NC}"
echo -e "${BOLD}SwiftPay directory:${NC} ${SWIFTPAY_DIR}"
echo -e "${GREEN}Keep this terminal open, then run ./run-tests.sh separately.${NC}"
echo ""

cd "$SWIFTPAY_DIR"
chmod +x ./start.sh
exec ./start.sh
