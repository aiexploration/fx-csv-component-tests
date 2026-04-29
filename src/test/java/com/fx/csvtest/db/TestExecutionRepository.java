package com.fx.csvtest.db;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TestExecutionRepository extends JpaRepository<TestExecutionRecord, Long> {

    Optional<TestExecutionRecord> findByTxId(String txId);

    long countByResultStatus(String resultStatus);
}
