.DEFAULT_GOAL := run
.PHONY: start-component inspect-queues run run-open install-swiftpay install-aut test clean help

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

## Run tests without reinstalling SwiftPay (faster if already installed)
test:
	@chmod +x run-tests.sh && ./run-tests.sh --skip-install

## Install SwiftPay into local Maven repo only
install-swiftpay:
	@cd $${SWIFTPAY_DIR:-../swiftpay} && mvn install -DskipTests -q && echo "SwiftPay installed"

## Backwards-compatible alias
install-aut: install-swiftpay

## Clean build artifacts
clean:
	mvn clean

## Help
help:
	@echo ""
	@echo "  make start-component  - start SwiftPay separately with exposed embedded H2/Artemis"
	@echo "  make inspect-queues   - open read-only Artemis queue inspector"
	@echo "  make run              - install SwiftPay and run CSV component tests"
	@echo "  make run-open         - run tests + open HTML report in browser"
	@echo "  make test             - run without reinstalling SwiftPay"
	@echo "  make install-swiftpay - install SwiftPay only"
	@echo "  make clean            - remove build artifacts"
	@echo ""
	@echo "  Override component path with SWIFTPAY_DIR=/path/to/swiftpay"
	@echo ""
