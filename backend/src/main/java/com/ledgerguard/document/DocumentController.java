package com.ledgerguard.document;

import com.ledgerguard.common.PageResponse;
import com.ledgerguard.document.Document.DocType;
import com.ledgerguard.document.Document.Status;
import com.ledgerguard.document.DocumentDtos.ApproveRequest;
import com.ledgerguard.document.DocumentDtos.CreateJournalRequest;
import com.ledgerguard.document.DocumentDtos.DocumentListItem;
import com.ledgerguard.document.DocumentDtos.DocumentResponse;
import com.ledgerguard.document.DocumentDtos.RejectRequest;
import com.ledgerguard.document.DocumentDtos.SummaryResponse;
import com.ledgerguard.document.DocumentDtos.UpdateFieldsRequest;
import com.ledgerguard.journal.JournalDtos.JournalEntryResponse;
import com.ledgerguard.security.AuthUser;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/documents")
public class DocumentController {

    private final DocumentService service;

    public DocumentController(DocumentService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentResponse upload(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                   @RequestParam("docType") DocType docType,
                                   @RequestParam("file") MultipartFile file) throws IOException {
        return service.upload(user.userId(), companyId, docType, file.getOriginalFilename(), file.getBytes());
    }

    @GetMapping
    public PageResponse<DocumentListItem> list(
            @AuthenticationPrincipal AuthUser user,
            @PathVariable Long companyId,
            @RequestParam(required = false) Status status,
            @RequestParam(required = false) DocType docType,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(service.list(user.userId(), companyId, new DocumentService.Filter(status, docType), pageable));
    }

    @GetMapping("/summary")
    public SummaryResponse summary(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId) {
        return service.summary(user.userId(), companyId);
    }

    @GetMapping("/{id}")
    public DocumentResponse get(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                @PathVariable Long id) {
        return service.get(user.userId(), companyId, id);
    }

    /** 원본 파일. 저장 시 판별한 형식으로만 내려주고 브라우저의 형식 추측(sniffing)을 막는다. */
    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> file(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                       @PathVariable Long id) {
        DocumentService.FileContent file = service.file(user.userId(), companyId, id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(file.fileName(), java.nio.charset.StandardCharsets.UTF_8)
                                .build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .body(file.content());
    }

    @PutMapping("/{id}/fields")
    public DocumentResponse updateFields(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                         @PathVariable Long id, @Valid @RequestBody UpdateFieldsRequest request) {
        return service.updateFields(user.userId(), companyId, id, request);
    }

    @PostMapping("/{id}/approve")
    public DocumentResponse approve(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                    @PathVariable Long id, @Valid @RequestBody ApproveRequest request) {
        return service.approve(user.userId(), companyId, id, request);
    }

    @PostMapping("/{id}/reject")
    public DocumentResponse reject(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                   @PathVariable Long id, @Valid @RequestBody RejectRequest request) {
        return service.reject(user.userId(), companyId, id, request.reason());
    }

    @PostMapping("/{id}/retry")
    public DocumentResponse retry(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                  @PathVariable Long id) {
        return service.retry(user.userId(), companyId, id);
    }

    @PostMapping("/{id}/journal-entry")
    @ResponseStatus(HttpStatus.CREATED)
    public JournalEntryResponse createJournalEntry(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                                   @PathVariable Long id, @Valid @RequestBody CreateJournalRequest request) {
        return service.createJournalEntry(user.userId(), companyId, id, request);
    }
}
