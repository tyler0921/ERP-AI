package com.ledgerguard.document.infra;

/** 증빙 원본 파일 저장소. 구현: SeaweedFS filer. */
public interface FileStorage {

    /** @param key "companies/{cid}/documents/{uuid}.{ext}" 형식의 저장 경로 */
    void put(String key, byte[] content, String contentType);

    byte[] get(String key);

    /** 저장소 장애. 호출한 쪽에서 503으로 바꾼다. */
    class StorageException extends RuntimeException {
        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
