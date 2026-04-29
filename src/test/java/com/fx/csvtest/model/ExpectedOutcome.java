package com.fx.csvtest.model;

/** Expected routing outcome for a test case. */
public enum ExpectedOutcome {
    /** Message should pass XSD validation and appear on fx.payment.valid. */
    VALID,
    /** Message should fail XSD validation and appear on fx.payment.invalid. */
    INVALID
}
