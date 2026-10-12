package com.ledgerguard.rules;

import com.ledgerguard.rules.RuleDocument.Category;
import com.ledgerguard.rules.RuleDtos.ArchiveRequest;
import com.ledgerguard.rules.RuleDtos.ChunkView;
import com.ledgerguard.rules.RuleDtos.DocumentView;
import com.ledgerguard.security.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Validated
@RequestMapping("/api/v1/companies/{companyId}/rules/documents")
public class RuleDocumentController {

    private final RuleDocumentService service;

    public RuleDocumentController(RuleDocumentService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentView upload(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                               @RequestParam("title") @NotBlank @Size(max = 200) String title,
                               @RequestParam("category") Category category,
                               @RequestParam("file") MultipartFile file) throws IOException {
        return service.upload(user.userId(), companyId, title, category, file.getOriginalFilename(), file.getBytes());
    }

    @GetMapping
    public List<DocumentView> list(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId) {
        return service.list(user.userId(), companyId);
    }

    @GetMapping("/{id}")
    public DocumentView get(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId, @PathVariable Long id) {
        return service.get(user.userId(), companyId, id);
    }

    @GetMapping("/{id}/chunks")
    public List<ChunkView> chunks(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                  @PathVariable Long id) {
        return service.chunks(user.userId(), companyId, id);
    }

    /** 원본 파일. 문서 형식(DOCX·텍스트)이 브라우저에서 열리지 않도록 항상 내려받기로 준다 */
    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> file(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                       @PathVariable Long id) {
        RuleDocumentService.FileContent file = service.file(user.userId(), companyId, id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .body(file.content());
    }

    @PostMapping("/{id}/reindex")
    public DocumentView reindex(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                @PathVariable Long id) {
        return service.reindex(user.userId(), companyId, id);
    }

    @PostMapping("/{id}/archive")
    public DocumentView archive(@AuthenticationPrincipal AuthUser user, @PathVariable Long companyId,
                                @PathVariable Long id, @Valid @RequestBody ArchiveRequest request) {
        return service.archive(user.userId(), companyId, id, request.reason());
    }
}
