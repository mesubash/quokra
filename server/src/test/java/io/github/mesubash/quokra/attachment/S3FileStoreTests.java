package io.github.mesubash.quokra.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

// Against Garage, a small S3-compatible server, through the AWS SDK like real S3.
@Testcontainers
class S3FileStoreTests {

    static final String KEY_ID = "GK000000000000000000000001";
    static final String SECRET = "0000000000000000000000000000000000000000000000000000000000000001";

    static final String CONFIG = """
        metadata_dir = "/var/lib/garage/meta"
        data_dir = "/var/lib/garage/data"
        db_engine = "sqlite"
        replication_factor = 1
        rpc_bind_addr = "[::]:3901"
        rpc_public_addr = "127.0.0.1:3901"
        rpc_secret = "0000000000000000000000000000000000000000000000000000000000000000"

        [s3_api]
        s3_region = "garage"
        api_bind_addr = "[::]:3900"
        """;

    @Container
    static final GenericContainer<?> GARAGE = new GenericContainer<>("dxflrs/garage:v2.5.0")
        .withCopyToContainer(Transferable.of(CONFIG), "/etc/garage.toml")
        .withExposedPorts(3900)
        .waitingFor(Wait.forLogMessage(".*S3 API server listening.*", 1));

    // One node, a key and a bucket, as `garage` itself would set them up.
    @BeforeAll
    static void setUp() throws Exception {
        String node = garage("node", "id", "-q").substring(0, 16);
        garage("layout", "assign", "-z", "dc1", "-c", "1G", node);
        garage("layout", "apply", "--version", "1");
        garage("key", "import", "--yes", "-n", "test", KEY_ID, SECRET);
        garage("bucket", "create", "chat");
        garage("bucket", "allow", "--read", "--write", "chat", "--key", "test");
    }

    static String garage(String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "/garage";
        System.arraycopy(args, 0, command, 1, args.length);
        var result = GARAGE.execInContainer(command);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException(String.join(" ", args) + ": " + result.getStderr());
        }
        return result.getStdout().strip();
    }

    @Test
    void storesAndReadsBack() {
        S3Client s3 = S3Client.builder()
            .region(Region.of("garage"))
            .endpointOverride(URI.create("http://" + GARAGE.getHost() + ":" + GARAGE.getMappedPort(3900)))
            .forcePathStyle(true)
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(KEY_ID, SECRET)))
            .build();
        var store = new S3FileStore(s3, "chat");

        store.put("t/c/one", ImagesTests.PNG, "image/png");

        assertThat(store.get("t/c/one")).isEqualTo(ImagesTests.PNG);
    }
}
