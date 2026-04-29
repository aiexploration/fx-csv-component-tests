.DEFAULT_GOAL := run
.PHONY: run run-open install-aut test clean help

## One-click: install AUT + run all CSV component tests
run:
	@chmod +x run-tests.sh && ./run-tests.sh

## Run tests and open the HTML report afterwards
run-open:
	@chmod +x run-tests.sh && ./run-tests.sh --open-report

## Run tests without reinstalling the AUT (faster if AUT already installed)
test:
	@chmod +x run-tests.sh && ./run-tests.sh --skip-install

## Install AUT into local Maven repo only
install-aut:
	@cd ../fx-payment-processor && mvn install -DskipTests -q && echo "AUT installed"

## Clean build artifacts
clean:
	mvn clean

## Help
help:
	@echo ""
	@echo "  make run        – install AUT and run all CSV component tests"
	@echo "  make run-open   – run tests + open HTML report in browser"
	@echo "  make test       – run without reinstalling AUT"
	@echo "  make install-aut – install AUT only"
	@echo "  make clean      – remove build artifacts"
	@echo ""
