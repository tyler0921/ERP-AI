package com.ledgerguard.bank;

import com.ledgerguard.account.Account;
import com.ledgerguard.account.AccountRepository;
import com.ledgerguard.account.AccountType;
import com.ledgerguard.audit.AuditActions;
import com.ledgerguard.audit.AuditService;
import com.ledgerguard.bank.BankCsvParser.ParseResult;
import com.ledgerguard.bank.BankCsvParser.ParsedRow;
import com.ledgerguard.bank.BankDtos.BankAccountResponse;
import com.ledgerguard.bank.BankDtos.CreateBankAccountRequest;
import com.ledgerguard.bank.BankDtos.ImportResponse;
import com.ledgerguard.bank.BankDtos.UpdateBankAccountRequest;
import com.ledgerguard.common.ApiError;
import com.ledgerguard.common.ApiException;
import com.ledgerguard.common.ErrorCode;
import com.ledgerguard.company.CompanyAccessService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BankService {

    private final BankAccountRepository bankAccounts;
    private final BankStatementImportRepository imports;
    private final BankTransactionRepository bankTransactions;
    private final AccountRepository accounts;
    private final CompanyAccessService access;
    private final AuditService audit;
    private final BankCsvParser parser;

    public BankService(BankAccountRepository bankAccounts, BankStatementImportRepository imports,
                       BankTransactionRepository bankTransactions, AccountRepository accounts,
                       CompanyAccessService access, AuditService audit, BankCsvParser parser) {
        this.bankAccounts = bankAccounts;
        this.imports = imports;
        this.bankTransactions = bankTransactions;
        this.accounts = accounts;
        this.access = access;
        this.audit = audit;
        this.parser = parser;
    }

    // ------------------------------------------------------------- bank accounts

    @Transactional
    public BankAccountResponse createAccount(Long userId, Long companyId, CreateBankAccountRequest req) {
        access.requireWrite(userId, companyId);
        Account gl = accounts.findByIdAndCompanyId(req.glAccountId(), companyId)
                .orElseThrow(() -> new ApiException(ErrorCode.BANK_ACCOUNT_GL_INVALID));
        if (gl.getType() != AccountType.ASSET || !gl.isActive()) {
            throw new ApiException(ErrorCode.BANK_ACCOUNT_GL_INVALID);
        }
        if (bankAccounts.existsByCompanyIdAndGlAccountId(companyId, gl.getId())) {
            throw new ApiException(ErrorCode.BANK_ACCOUNT_GL_DUPLICATE);
        }

        BankAccount account = new BankAccount();
        account.setCompanyId(companyId);
        account.setName(req.name().trim());
        account.setBankName(blankToNull(req.bankName()));
        account.setAccountNoLast4(last4(req.accountNo()));
        account.setGlAccountId(gl.getId());
        account.setCreatedBy(userId);
        bankAccounts.saveAndFlush(account);

        BankAccountResponse response = toResponse(account, gl);
        audit.record(companyId, userId, AuditActions.BANK_ACCOUNT_CREATE, AuditActions.TARGET_BANK_ACCOUNT,
                account.getId(), null, response);
        return response;
    }

    @Transactional(readOnly = true)
    public List<BankAccountResponse> listAccounts(Long userId, Long companyId) {
        access.requireMember(userId, companyId);
        List<BankAccount> list = bankAccounts.findByCompanyIdOrderById(companyId);
        Map<Long, Account> gl = new HashMap<>();
        accounts.findAllByIdInAndCompanyId(list.stream().map(BankAccount::getGlAccountId).toList(), companyId)
                .forEach(a -> gl.put(a.getId(), a));
        return list.stream().map(b -> toResponse(b, gl.get(b.getGlAccountId()))).toList();
    }

    @Transactional
    public BankAccountResponse updateAccount(Long userId, Long companyId, Long id, UpdateBankAccountRequest req) {
        access.requireWrite(userId, companyId);
        BankAccount account = findAccount(companyId, id);
        Account gl = accounts.findByIdAndCompanyId(account.getGlAccountId(), companyId).orElse(null);
        BankAccountResponse before = toResponse(account, gl);

        if (req.name() != null) {
            account.setName(req.name().trim());
        }
        if (req.bankName() != null) {
            account.setBankName(blankToNull(req.bankName()));
        }
        if (req.active() != null) {
            account.setActive(req.active());
        }
        bankAccounts.saveAndFlush(account);

        BankAccountResponse after = toResponse(account, gl);
        audit.record(companyId, userId, AuditActions.BANK_ACCOUNT_UPDATE, AuditActions.TARGET_BANK_ACCOUNT, id,
                before, after);
        return after;
    }

    // ------------------------------------------------------------------- imports

    /**
     * CSV를 파싱해 은행 거래로 저장한다. 잘못된 행이 있으면 아무것도 저장하지 않는다(파서가 예외).
     * 이미 등록된 거래(같은 dedupeKey)는 건너뛴다. 같은 계좌의 업로드는 계좌 행 잠금으로 직렬화한다.
     */
    @Transactional
    public ImportResponse importCsv(Long userId, Long companyId, Long bankAccountId, String fileName, byte[] content) {
        access.requireWrite(userId, companyId);
        BankAccount account = bankAccounts.lockByIdAndCompanyId(bankAccountId, companyId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!account.isActive()) {
            throw new ApiException(ErrorCode.BANK_ACCOUNT_INACTIVE);
        }
        ParseResult parsed = parser.parse(content);

        // 파일 안에서 완전히 같은 행이 여러 번 나오면(예: 같은 날 같은 수수료) 등장 순번으로 구분한다.
        // 같은 파일을 다시 올리면 같은 키가 만들어져 중복으로 건너뛴다.
        Map<String, Integer> occurrences = new HashMap<>();
        Map<String, ParsedRow> byKey = new LinkedHashMap<>();
        for (ParsedRow row : parsed.rows()) {
            String base = String.join("|", row.date().toString(), Objects.toString(row.time(), ""),
                    row.direction().name(), row.amount().toPlainString(),
                    row.balance() == null ? "" : row.balance().toPlainString(),
                    Objects.toString(row.counterparty(), ""), Objects.toString(row.memo(), ""));
            int n = occurrences.merge(base, 1, Integer::sum);
            byKey.put(sha256(base + "|" + n), row);
        }
        Set<String> existing = new HashSet<>(bankTransactions.findExistingDedupeKeys(account.getId(), byKey.keySet()));

        BankStatementImport imp = new BankStatementImport();
        imp.setCompanyId(companyId);
        imp.setBankAccountId(account.getId());
        imp.setFileName(sanitizeFileName(fileName));
        imp.setFileSha256(sha256(content));
        imp.setEncoding(parsed.encoding());
        imp.setTotalRows(parsed.rows().size());
        imp.setImportedRows(parsed.rows().size() - existing.size());
        imp.setDuplicateRows(existing.size());
        imp.setCreatedBy(userId);
        imports.saveAndFlush(imp);

        List<BankTransaction> toSave = new ArrayList<>();
        byKey.forEach((key, row) -> {
            if (existing.contains(key)) {
                return;
            }
            BankTransaction tx = new BankTransaction();
            tx.setCompanyId(companyId);
            tx.setBankAccountId(account.getId());
            tx.setImportId(imp.getId());
            tx.setRowNo(row.rowNo());
            tx.setTxDate(row.date());
            tx.setTxTime(row.time());
            tx.setDirection(row.direction());
            tx.setAmount(row.amount());
            tx.setBalance(row.balance());
            tx.setCounterparty(row.counterparty());
            tx.setMemo(row.memo());
            tx.setDedupeKey(key);
            tx.setStatus(BankTxStatus.UNMATCHED);
            toSave.add(tx);
        });
        bankTransactions.saveAll(toSave);

        ImportResponse response = ImportResponse.from(imp);
        audit.record(companyId, userId, AuditActions.BANK_IMPORT, AuditActions.TARGET_BANK_IMPORT, imp.getId(),
                null, response);
        return response;
    }

    @Transactional(readOnly = true)
    public List<ImportResponse> listImports(Long userId, Long companyId, Long bankAccountId) {
        access.requireMember(userId, companyId);
        findAccount(companyId, bankAccountId);
        return imports.findTop50ByCompanyIdAndBankAccountIdOrderByIdDesc(companyId, bankAccountId).stream()
                .map(ImportResponse::from)
                .toList();
    }

    // ------------------------------------------------------------------- helpers

    public BankAccount findAccount(Long companyId, Long id) {
        return bankAccounts.findByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private static BankAccountResponse toResponse(BankAccount b, Account gl) {
        return new BankAccountResponse(b.getId(), b.getName(), b.getBankName(),
                b.getAccountNoLast4() == null ? null : "****" + b.getAccountNoLast4(),
                b.getGlAccountId(), gl == null ? null : gl.getCode(), gl == null ? null : gl.getName(),
                b.isActive(), b.getCreatedAt());
    }

    private static String last4(String accountNo) {
        if (accountNo == null || accountNo.isBlank()) {
            return null;
        }
        String digits = accountNo.replaceAll("\\D", "");
        if (digits.length() < 4) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(),
                    List.of(new ApiError.Detail("accountNo", "계좌번호는 숫자 4자리 이상이어야 합니다.")));
        }
        return digits.substring(digits.length() - 4);
    }

    /** 경로 구분자·제어문자를 제거한 파일명 (표시용) */
    private static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "statement.csv";
        }
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "");
        return base.length() > 255 ? base.substring(0, 255) : base;
    }

    private static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
