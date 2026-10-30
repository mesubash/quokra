package io.github.mesubash.quokra.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mesubash.quokra.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ImagesTests {

    public static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};

    @Test
    void knowsImagesByTheirFirstBytes() {
        assertThat(Images.typeOf(PNG)).isEqualTo("image/png");
        assertThat(Images.typeOf(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0})).isEqualTo("image/jpeg");
        assertThat(Images.typeOf("GIF89a...".getBytes(StandardCharsets.US_ASCII))).isEqualTo("image/gif");
        assertThat(Images.typeOf("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.US_ASCII))).isEqualTo("image/webp");
    }

    @Test
    void refusesEverythingElse() {
        for (String other : new String[] {"<svg onload=alert(1)>", "%PDF-1.7", "hello", ""}) {
            assertThatThrownBy(() -> Images.typeOf(other.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("UNSUPPORTED_FILE"));
        }
        byte[] huge = new byte[Images.MAX_BYTES + 1];
        System.arraycopy(PNG, 0, huge, 0, PNG.length);
        assertThatThrownBy(() -> Images.typeOf(huge))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("FILE_TOO_LARGE"));
    }

    @Test
    void diskStoreKeepsFilesInsideItsFolder(@TempDir Path dir) {
        var store = new DiskFileStore(dir);
        store.put("t/c/one", PNG, "image/png");

        assertThat(store.get("t/c/one")).isEqualTo(PNG);
        assertThatThrownBy(() -> store.get("../outside")).isInstanceOf(IllegalArgumentException.class);
    }
}
