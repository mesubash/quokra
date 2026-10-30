package io.github.mesubash.quokra.attachment;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

// Files in an S3-compatible bucket (AWS S3, Cloudflare R2, MinIO).
class S3FileStore implements FileStore {

    private final S3Client s3;
    private final String bucket;

    S3FileStore(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        s3.putObject(r -> r.bucket(bucket).key(key).contentType(contentType), RequestBody.fromBytes(bytes));
    }

    @Override
    public byte[] get(String key) {
        return s3.getObjectAsBytes(r -> r.bucket(bucket).key(key)).asByteArray();
    }
}
