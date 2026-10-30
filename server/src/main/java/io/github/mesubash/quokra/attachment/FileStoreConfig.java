package io.github.mesubash.quokra.attachment;

import java.net.URI;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

// quokra.attachments.store: disk (default) or s3. S3 credentials come from the usual AWS settings
// (AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY, or the instance's role).
@Configuration
class FileStoreConfig {

    @Bean
    @ConditionalOnProperty(name = "quokra.attachments.store", havingValue = "disk", matchIfMissing = true)
    FileStore diskFileStore(@Value("${quokra.attachments.dir}") String dir) {
        return new DiskFileStore(Path.of(dir));
    }

    @Bean
    @ConditionalOnProperty(name = "quokra.attachments.store", havingValue = "s3")
    FileStore s3FileStore(@Value("${quokra.attachments.s3.bucket}") String bucket,
                          @Value("${quokra.attachments.s3.region}") String region,
                          @Value("${quokra.attachments.s3.endpoint:}") String endpoint) {
        var builder = S3Client.builder().region(Region.of(region));
        if (!endpoint.isBlank()) {
            // R2, Garage, MinIO: their own address, bucket names in the path rather than the host, and only the
            // checksums S3 itself requires (the SDK's newer defaults are refused by some of them).
            builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
        }
        return new S3FileStore(builder.build(), bucket);
    }
}
