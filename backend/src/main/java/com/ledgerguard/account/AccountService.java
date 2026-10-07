package com.ledgerguard.account;

import com.ledgerguard.account.AccountDtos.AccountResponse;
import com.ledgerguard.account.AccountDtos.CreateAccountRequest;
import com.ledgerguard.account.AccountDtos.UpdateAccountRequest;
import com.ledgerguard.audit.AuditActions;
import com.ledgerguard.audit.AuditService;
import com.ledgerguard.common.ApiException;
import com.ledgerguard.common.ErrorCode;
import com.ledgerguard.company.CompanyAccessService;
import com.ledgerguard.journal.JournalEntryLineRepository;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    /** 회사 생성 시 채워지는 기본 계정과목. 1xxx 자산, 2xxx 부채, 3xxx 자본, 4xxx 수익, 5xxx 비용. */
    private static final List<DefaultAccount> DEFAULTS = List.of(
            new DefaultAccount("1010", "현금", AccountType.ASSET),
            new DefaultAccount("1020", "보통예금", AccountType.ASSET),
            new DefaultAccount("1100", "외상매출금", AccountType.ASSET),
            new DefaultAccount("1200", "선급금", AccountType.ASSET),
            new DefaultAccount("1350", "부가세대급금", AccountType.ASSET),
            new DefaultAccount("2010", "외상매입금", AccountType.LIABILITY),
            new DefaultAccount("2020", "미지급금", AccountType.LIABILITY),
            new DefaultAccount("2100", "부가세예수금", AccountType.LIABILITY),
            new DefaultAccount("2200", "예수금", AccountType.LIABILITY),
            new DefaultAccount("3010", "자본금", AccountType.EQUITY),
            new DefaultAccount("3100", "이익잉여금", AccountType.EQUITY),
            new DefaultAccount("4010", "상품매출", AccountType.REVENUE),
            new DefaultAccount("4020", "용역매출", AccountType.REVENUE),
            new DefaultAccount("5100", "급여", AccountType.EXPENSE),
            new DefaultAccount("5200", "복리후생비", AccountType.EXPENSE),
            new DefaultAccount("5300", "접대비", AccountType.EXPENSE),
            new DefaultAccount("5400", "광고선전비", AccountType.EXPENSE),
            new DefaultAccount("5500", "임차료", AccountType.EXPENSE),
            new DefaultAccount("5600", "소모품비", AccountType.EXPENSE),
            new DefaultAccount("5700", "지급수수료", AccountType.EXPENSE),
            new DefaultAccount("5800", "운반비", AccountType.EXPENSE),
            new DefaultAccount("5900", "외주비", AccountType.EXPENSE));

    private record DefaultAccount(String code, String name, AccountType type) {
    }

    private final AccountRepository accounts;
    private final JournalEntryLineRepository lines;
    private final CompanyAccessService access;
    private final AuditService audit;

    public AccountService(AccountRepository accounts, JournalEntryLineRepository lines,
                          CompanyAccessService access, AuditService audit) {
        this.accounts = accounts;
        this.lines = lines;
        this.access = access;
        this.audit = audit;
    }

    /** 회사 생성 트랜잭션 안에서 호출된다. 권한 검사는 호출자가 이미 마쳤다. */
    @Transactional
    public void seedDefaults(Long companyId) {
        accounts.saveAll(DEFAULTS.stream()
                .map(d -> new Account(companyId, d.code(), d.name(), d.type()))
                .toList());
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> list(Long userId, Long companyId, AccountType type, Boolean active) {
        access.requireMember(userId, companyId);
        Specification<Account> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("companyId"), companyId));
            if (type != null) {
                p.add(cb.equal(root.get("type"), type));
            }
            if (active != null) {
                p.add(cb.equal(root.get("active"), active));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        return accounts.findAll(spec, Sort.by("code")).stream().map(AccountResponse::from).toList();
    }

    @Transactional
    public AccountResponse create(Long userId, Long companyId, CreateAccountRequest req) {
        access.requireWrite(userId, companyId);
        if (accounts.existsByCompanyIdAndCode(companyId, req.code())) {
            throw new ApiException(ErrorCode.ACCOUNT_CODE_DUPLICATE);
        }
        Account account = accounts.save(new Account(companyId, req.code(), req.name().trim(), req.type()));
        AccountResponse response = AccountResponse.from(account);
        audit.record(companyId, userId, AuditActions.ACCOUNT_CREATE, AuditActions.TARGET_ACCOUNT, account.getId(),
                null, response);
        return response;
    }

    @Transactional
    public AccountResponse update(Long userId, Long companyId, Long id, UpdateAccountRequest req) {
        access.requireWrite(userId, companyId);
        Account account = accounts.findByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        AccountResponse before = AccountResponse.from(account);

        boolean codeChanged = req.code() != null && !req.code().equals(account.getCode());
        boolean typeChanged = req.type() != null && req.type() != account.getType();
        if ((codeChanged || typeChanged) && lines.existsByAccountId(account.getId())) {
            throw new ApiException(ErrorCode.ACCOUNT_IN_USE);
        }
        if (codeChanged) {
            if (accounts.existsByCompanyIdAndCode(companyId, req.code())) {
                throw new ApiException(ErrorCode.ACCOUNT_CODE_DUPLICATE);
            }
            account.setCode(req.code());
        }
        if (typeChanged) {
            account.setType(req.type());
        }
        if (req.name() != null) {
            account.setName(req.name().trim());
        }
        if (req.active() != null) {
            account.setActive(req.active());
        }

        AccountResponse after = AccountResponse.from(account);
        audit.record(companyId, userId, AuditActions.ACCOUNT_UPDATE, AuditActions.TARGET_ACCOUNT, account.getId(),
                before, after);
        return after;
    }
}
