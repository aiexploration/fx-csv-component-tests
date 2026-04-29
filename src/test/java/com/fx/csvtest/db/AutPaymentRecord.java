package com.fx.csvtest.db;

import java.time.LocalDateTime;

public record AutPaymentRecord(
        String id,
        String transactionId,
        String status,
        String validationErrors,
        LocalDateTime createdAt) {
}
