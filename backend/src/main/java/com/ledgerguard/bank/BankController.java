package com.ledgerguard.bank;

import com.ledgerguard.bank.BankDtos.BankAccountResponse;
import com.ledgerguard.bank.BankDtos.CreateBankAccountRequest;
import com.ledgerguard.bank.BankDtos.ImportResponse;
import com.ledgerguard.bank.BankDtos.UpdateBankAccountRequest;
import com.ledgerguard.common.ApiError;
import com.ledgerguard.common.ApiException;
import com.ledgerguard.common.ErrorCode;
import com.ledgerguard.security.AuthUser;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/bank-accounts")
public class BankController {

    private static final long MAX_CSV_BYTES = 2L * 1024 * 1024;

    private final BankService service;

    public BankController(BankService service) {
        this.service = service;
    }

    @GetMapping
    public List<BankAccountResponse> list(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId) {
        return service.listAccounts(user.userId(), companyId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BankAccountResponse create(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                      @Valid @RequestBody CreateBankAccountRequest request) {
        return service.createAccount(user.userId(), companyId, request);
    }

    @PatchMapping("/{id}")
    public BankAccountResponse update(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                      @PathVariable Long id, @Valid @RequestBody UpdateBankAccountRequest request) {
        return service.updateAccount(user.userId(), companyId, id, request);
    }

    @PostMapping(path = "/{id}/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ImportResponse importCsv(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                    @PathVariable Long id, @RequestParam("file") MultipartFile file) throws IOException {
        if (file.getSize() > MAX_CSV_BYTES) {
            throw new ApiException(ErrorCode.IMPORT_FILE_TOO_LARGE);
        }
        String name = file.getOriginalFilename();
        if (name != null && !name.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new ApiException(ErrorCode.IMPORT_FILE_INVALID, ErrorCode.IMPORT_FILE_INVALID.defaultMessage(),
                    List.of(new ApiError.Detail("file", "CSV(.csv) 파일만 업로드할 수 있습니다.")));
        }
        return service.importCsv(user.userId(), companyId, id, name, file.getBytes());
    }

    @GetMapping("/{id}/imports")
    public List<ImportResponse> imports(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                        @PathVariable Long id) {
        return service.listImports(user.userId(), companyId, id);
    }
}
