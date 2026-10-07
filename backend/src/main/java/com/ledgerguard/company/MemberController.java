package com.ledgerguard.company;

import com.ledgerguard.company.CompanyDtos.AddMemberRequest;
import com.ledgerguard.company.CompanyDtos.ChangeRoleRequest;
import com.ledgerguard.company.CompanyDtos.MemberResponse;
import com.ledgerguard.security.AuthUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/members")
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    @GetMapping
    public List<MemberResponse> list(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId) {
        return memberService.list(user.userId(), companyId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MemberResponse add(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                              @Valid @RequestBody AddMemberRequest request) {
        return memberService.add(user.userId(), companyId, request);
    }

    @PatchMapping("/{userId}")
    public MemberResponse changeRole(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                     @PathVariable Long userId, @Valid @RequestBody ChangeRoleRequest request) {
        return memberService.changeRole(user.userId(), companyId, userId, request);
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                       @PathVariable Long userId) {
        memberService.remove(user.userId(), companyId, userId);
    }
}
