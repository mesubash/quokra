package io.github.mesubash.quokra.attachment;

// Where attachment files live: a folder on disk or an S3-compatible bucket (quokra.attachments.store).
public interface FileStore {

    void put(String key, byte[] bytes, String contentType);

    byte[] get(String key);
}
