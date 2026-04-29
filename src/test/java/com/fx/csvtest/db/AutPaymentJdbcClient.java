package com.fx.csvtest.db;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class AutPaymentJdbcClient {

    private final JdbcTemplate jdbcTemplate;

    public Set<String> existingIdsForTransactionId(String txId) {
        return jdbcTemplate.queryForList(
                        "select id from payment_message where transaction_id = ?",
                        String.class,
                        txId)
                .stream()
                .collect(Collectors.toSet());
    }

    public Optional<AutPaymentRecord> latestNewPaymentForTxId(String txId, Set<String> existingIds) {
        return jdbcTemplate.query(
                        "select id, transaction_id, status, validation_errors, created_at " +
                                "from payment_message where transaction_id = ? order by created_at desc",
                        (rs, rowNum) -> map(rs),
                        txId)
                .stream()
                .filter(record -> !existingIds.contains(record.id()))
                .findFirst();
    }

    public long countByStatus(String status) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from payment_message where status = ?",
                Long.class,
                status);
        return count == null ? 0 : count;
    }

    public Optional<AutPaymentRecord> latestByStatus(String status) {
        List<AutPaymentRecord> records = jdbcTemplate.query(
                "select id, transaction_id, status, validation_errors, created_at " +
                        "from payment_message where status = ? order by created_at desc limit 1",
                (rs, rowNum) -> map(rs),
                status);
        return records.stream().findFirst();
    }

    private AutPaymentRecord map(ResultSet rs) throws SQLException {
        return new AutPaymentRecord(
                rs.getString("id"),
                rs.getString("transaction_id"),
                rs.getString("status"),
                rs.getString("validation_errors"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }
}
