package com.fx.csvtest.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Registers all test-framework beans (loaders, factories, orchestrators, reporters)
 * so they are available when the full Spring context is bootstrapped via
 * {@code @SpringBootTest(classes = {FxPaymentProcessorApplication.class, CsvTestSuiteConfig.class})}.
 *
 * Explicitly declares JPA scanning across both the AUT and test-framework packages
 * because {@code FxPaymentProcessorApplication}'s auto-scan only covers {@code com.fx.payment}.
 */
@Configuration
@ComponentScan("com.fx.csvtest")
@EnableJpaRepositories(basePackages = {"com.fx.payment", "com.fx.csvtest"})
@EntityScan(basePackages = {"com.fx.payment", "com.fx.csvtest"})
public class CsvTestSuiteConfig {
}
