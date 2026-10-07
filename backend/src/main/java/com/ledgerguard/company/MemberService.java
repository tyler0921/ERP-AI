package com.ledgerguard.company;

import com.ledgerguard.audit.AuditActions;
import com.ledgerguard.audit.AuditService;
import com.ledgerguard.common.ApiException;
import com.ledgerguard.common.ErrorCode;
import com.ledgerguard.company.CompanyDtos.AddMemberRequest;
import com.ledgerguard.company.CompanyDtos.ChangeRoleRequest;
import com.ledgerguard.company.CompanyDtos.MemberResponse;
import com.ledgerguard.user.User;
import com.ledgerguard.user.UserRepository;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberService {

    private final UserCompanyRoleRepository roles;
    private final UserRepository users;
    private final CompanyRepository companies;
    private final CompanyAccessService access;
    private final AuditService audit;

    public MemberService(UserCompanyRoleRepository roles, UserRepository users, CompanyRepository companies,
                         CompanyAccessService access, AuditService audit) {
        this.roles = roles;
        this.users = users;
        this.companies = companies;
        this.access = access;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<MemberResponse> list(Long actorId, Long companyId) {
        access.requireMember(actorId, companyId);
        List<UserCompanyRole> memberships = roles.findByIdCompanyId(companyId);
        Map<Long, User> byId = users
                .findAllById(memberships.stream().map(m -> m.getId().getUserId()).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return memberships.stream()
                .map(m -> toResponse(byId.get(m.getId().getUserId()), m.getRole()))
                .sorted((a, b) -> Long.compare(a.userId(), b.userId()))
                .toList();
    }

    /** 이미 가입한 이메일만 추가할 수 있다. (초대 메일은 Phase 1 범위 밖) */
    @Transactional
    public MemberResponse add(Long actorId, Long companyId, AddMemberRequest req) {
        access.requireAdmin(actorId, companyId);
        User target = users.findByEmail(req.email().trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
        if (roles.findByIdUserIdAndIdCompanyId(target.getId(), companyId).isPresent()) {
            throw new ApiException(ErrorCode.MEMBER_ALREADY_EXISTS);
        }
        roles.save(new UserCompanyRole(target.getId(), companyId, req.role()));

        MemberResponse response = toResponse(target, req.role());
        audit.record(companyId, actorId, AuditActions.MEMBER_ADD, AuditActions.TARGET_MEMBER, target.getId(),
                null, response);
        return response;
    }

    @Transactional
    public MemberResponse changeRole(Long actorId, Long companyId, Long userId, ChangeRoleRequest req) {
        access.requireAdmin(actorId, companyId);
        lockCompany(companyId);
        UserCompanyRole membership = findMembership(userId, companyId);
        User target = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));

        MemberResponse before = toResponse(target, membership.getRole());
        if (membership.getRole() == Role.ADMIN && req.role() != Role.ADMIN) {
            requireAnotherAdmin(companyId);
        }
        membership.setRole(req.role());

        MemberResponse after = toResponse(target, req.role());
        audit.record(companyId, actorId, AuditActions.MEMBER_ROLE_CHANGE, AuditActions.TARGET_MEMBER, userId,
                before, after);
        return after;
    }

    @Transactional
    public void remove(Long actorId, Long companyId, Long userId) {
        access.requireAdmin(actorId, companyId);
        lockCompany(companyId);
        UserCompanyRole membership = findMembership(userId, companyId);
        User target = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));

        if (membership.getRole() == Role.ADMIN) {
            requireAnotherAdmin(companyId);
        }
        MemberResponse before = toResponse(target, membership.getRole());
        roles.delete(membership);

        audit.record(companyId, actorId, AuditActions.MEMBER_REMOVE, AuditActions.TARGET_MEMBER, userId,
                before, null);
    }

    private void lockCompany(Long companyId) {
        companies.lockById(companyId).orElseThrow(() -> new ApiException(ErrorCode.COMPANY_ACCESS_DENIED));
    }

    private UserCompanyRole findMembership(Long userId, Long companyId) {
        return roles.findByIdUserIdAndIdCompanyId(userId, companyId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "해당 멤버를 찾을 수 없습니다."));
    }

    /** 대상이 마지막 ADMIN이면 강등/제거를 막는다. */
    private void requireAnotherAdmin(Long companyId) {
        if (roles.countByIdCompanyIdAndRole(companyId, Role.ADMIN) <= 1) {
            throw new ApiException(ErrorCode.LAST_ADMIN_REQUIRED);
        }
    }

    private MemberResponse toResponse(User user, Role role) {
        return new MemberResponse(user.getId(), user.getEmail(), user.getName(), role);
    }
}
