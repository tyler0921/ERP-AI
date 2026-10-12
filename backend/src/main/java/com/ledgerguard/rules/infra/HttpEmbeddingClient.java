package com.ledgerguard.rules.infra;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** ai-service POST /v1/embed. 실패하면 호출한 쪽이 키워드 검색으로 대체한다. */
@Component
public class HttpEmbeddingClient implements EmbeddingClient {

    private final RestClient client;

    public HttpEmbeddingClient(RestClient.Builder builder, @Value("${app.ai-service.url}") String baseUrl,
                               @Value("${app.rag.embed-timeout-ms}") long timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.client = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public Embeddings embed(List<String> texts, String kind) {
        try {
            Embeddings result = client.post()
                    .uri("/v1/embed")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("texts", texts, "kind", kind))
                    .retrieve()
                    .body(Embeddings.class);
            if (result == null || result.vectors() == null || result.vectors().size() != texts.size()) {
                throw new EmbeddingUnavailableException("임베딩 응답이 올바르지 않습니다.", null);
            }
            return result;
        } catch (RestClientException e) {
            throw new EmbeddingUnavailableException("AI 서비스(임베딩)에 연결할 수 없습니다.", e);
        }
    }
}
