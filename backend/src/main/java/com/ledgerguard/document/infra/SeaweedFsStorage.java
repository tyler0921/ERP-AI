package com.ledgerguard.document.infra;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * SeaweedFS filer HTTP API로 파일을 저장한다. (S3 SDK 없이 POST multipart / GET)
 * filer는 docker 내부 네트워크에만 열려 있고, 브라우저는 Spring을 거쳐 권한 확인 후에만 파일을 받는다.
 */
@Component
public class SeaweedFsStorage implements FileStorage {

    private final RestClient client;
    private final String root;

    public SeaweedFsStorage(@Value("${app.storage.filer-url}") String filerUrl,
                            @Value("${app.storage.root}") String root) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(30000);
        this.client = RestClient.builder().baseUrl(filerUrl).requestFactory(factory).build();
        this.root = root.replaceAll("^/+|/+$", "");
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        String fileName = key.substring(key.lastIndexOf('/') + 1);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        try {
            client.post().uri("/" + root + "/" + key)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new StorageException("파일 저장소(SeaweedFS)에 저장하지 못했습니다.", e);
        }
    }

    @Override
    public byte[] get(String key) {
        try {
            return client.get().uri("/" + root + "/" + key).retrieve().body(byte[].class);
        } catch (RestClientException e) {
            throw new StorageException("파일 저장소(SeaweedFS)에서 파일을 읽지 못했습니다.", e);
        }
    }
}
