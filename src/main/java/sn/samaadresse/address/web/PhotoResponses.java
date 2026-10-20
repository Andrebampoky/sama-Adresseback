package sn.samaadresse.address.web;

import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

final class PhotoResponses {
    private PhotoResponses() {
    }

    /** Toujours servie en image/jpeg (réencodée par le serveur), sans interprétation par le navigateur. */
    static ResponseEntity<byte[]> jpeg(byte[] bytes, boolean publicCache) {
        CacheControl cache = publicCache
                ? CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic()
                : CacheControl.noStore();
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(cache)
                .header("X-Content-Type-Options", "nosniff")
                .body(bytes);
    }
}
