.DEFAULT_GOAL := run
.PHONY: start-component inspect-queues run run-open test clean help

## Start SwiftPay separately with exposed embedded H2 + Artemis
start-component:
	@chmod +x start-component.sh && ./start-component.sh

## Open an interactive read-only Artemis queue inspector
inspect-queues:
	@chmod +x inspect-queues.sh && ./inspect-queues.sh

## Run CSV component tests against an already-running SwiftPay component
run:
	@chmod +x run-tests.sh && ./run-tests.sh

## Run tests and open the HTML report afterwards
run-open:
	@chmod +x run-tests.sh && ./run-tests.sh --open-report

## Run tests
test:
	@chmod +x run-tests.sh && ./run-tests.sh

## Clean build artifacts
clean:
	mvn clean

## Help
help:
	@echo ""
	@echo "  make start-component  - start SwiftPay separately with exposed embedded H2/Artemis"
	@echo "  make inspect-queues   - open read-only Artemis queue inspector"
	@echo "  make run              - run CSV component tests"
	@echo "  make run-open         - run tests + open HTML report in browser"
	@echo "  make test             - run CSV component tests"
	@echo "  make clean            - remove build artifacts"
	@echo ""
	@echo "  Override component path with SWIFTPAY_DIR=/path/to/swiftpay"
	@echo ""
