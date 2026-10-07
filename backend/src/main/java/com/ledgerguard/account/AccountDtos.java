package com.ledgerguard.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AccountDtos {

    private AccountDtos() {
    }

    private static final String CODE_PATTERN = "^[A-Za-z0-9_-]+$";
    private static final String CODE_MESSAGE = "계정과목 코드는 영문/숫자/-/_만 사용할 수 있습니다.";

    public record CreateAccountRequest(
            @NotBlank @Size(max = 20) @Pattern(regexp = CODE_PATTERN, message = CODE_MESSAGE) String code,
            @NotBlank @Size(max = 100) String name,
            @NotNull AccountType type) {
    }

    /** null인 필드는 변경하지 않는다. code/type은 전표에서 사용 중이면 변경할 수 없다. */
    public record UpdateAccountRequest(
            @Size(min = 1, max = 20) @Pattern(regexp = CODE_PATTERN, message = CODE_MESSAGE) String code,
            @Size(min = 1, max = 100) String name,
            AccountType type,
            Boolean active) {
    }

    public record AccountResponse(Long id, String code, String name, AccountType type, boolean active) {
        public static AccountResponse from(Account account) {
            return new AccountResponse(account.getId(), account.getCode(), account.getName(), account.getType(),
                    account.isActive());
        }
    }
}
