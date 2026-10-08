package com.ledgerguard.anomaly.infra;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** FastAPI ai-service의 IsolationForest 점수 API. 동기 호출이며 실패하면 규칙 점수만 쓰도록 예외로 알린다. */
@Component
public class HttpAnomalyModelClient implements AnomalyModelClient {

    private final RestClient client;

    public HttpAnomalyModelClient(RestClient.Builder builder, @Value("${app.ai-service.url}") String baseUrl,
                                  @Value("${app.anomaly.ml-timeout-ms}") long timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.client = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public ModelResult score(List<Sample> samples, int minSamples) {
        try {
            ModelResult result = client.post()
                    .uri("/v1/anomaly/score")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("samples", samples, "minSamples", minSamples))
                    .retrieve()
                    .body(ModelResult.class);
            if (result == null || result.status() == null) {
                throw new ModelUnavailableException("AI 서비스 응답이 비어 있습니다.", null);
            }
            return result;
        } catch (RestClientException e) {
            throw new ModelUnavailableException("AI 서비스(이상탐지 모델)에 연결할 수 없습니다.", e);
        }
    }
}
