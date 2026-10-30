package io.github.mesubash.quokra.attachment;

import io.github.mesubash.quokra.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

// The images people may send, recognised by their first bytes rather than the name or the browser's word.
// No SVG: it can carry scripts.
public final class Images {

    public static final int MAX_BYTES = 5 * 1024 * 1024;

    private Images() {}

    // The image's content type, or an error for anything else.
    public static String typeOf(byte[] bytes) {
        if (bytes.length == 0) {
            throw unsupported();
        }
        if (bytes.length > MAX_BYTES) {
            throw tooLarge();
        }
        if (startsWith(bytes, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        if (startsWith(bytes, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (startsWith(bytes, 'G', 'I', 'F', '8')) {
            return "image/gif";
        }
        if (startsWith(bytes, 'R', 'I', 'F', 'F') && bytes.length >= 12
                && Arrays.equals(bytes, 8, 12, new byte[] {'W', 'E', 'B', 'P'}, 0, 4)) {
            return "image/webp";
        }
        throw unsupported();
    }

    // An image as a response: shown inline, never run as anything else, kept only in the viewer's own cache.
    public static ResponseEntity<byte[]> response(byte[] bytes, String contentType, String name) {
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(contentType))
            .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePrivate())
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.inline().filename(name, StandardCharsets.UTF_8).build().toString())
            .header("Content-Security-Policy", "default-src 'none'; sandbox")
            .header("X-Content-Type-Options", "nosniff")
            .body(bytes);
    }

    public static ApiException tooLarge() {
        return new ApiException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "Images can be up to 5 MB");
    }

    private static ApiException unsupported() {
        return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE",
            "Only images can be sent: PNG, JPEG, GIF or WebP");
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
