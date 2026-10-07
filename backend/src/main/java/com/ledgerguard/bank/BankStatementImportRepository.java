package com.ledgerguard.bank;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BankStatementImportRepository extends JpaRepository<BankStatementImport, Long> {

    List<BankStatementImport> findTop50ByCompanyIdAndBankAccountIdOrderByIdDesc(Long companyId, Long bankAccountId);
}
