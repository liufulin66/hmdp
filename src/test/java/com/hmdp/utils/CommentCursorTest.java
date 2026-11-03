package com.hmdp.utils;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;
class CommentCursorTest {
    @Test void preservesMicrosecondsAndIdsBeyondJavaScriptPrecision() {
        var cursor=new CommentCursor(LocalDateTime.of(2026,11,3,8,12,13,123456000),9007199254740993L);
        assertEquals(cursor,CommentCursor.decode(cursor.encode())); assertNull(CommentCursor.decode(null));
    }
    @Test void rejectsMalformedIncompleteOrNoncanonicalCursors() {
        for(String value:new String[]{"","!","abc","a".repeat(121),encoded("2026-11-03T08:00:00.123456|0"),
                encoded("2026-11-03T08:00:00.123456|9223372036854775808"),encoded("2026-02-30T08:00:00.123456|1"),
                encoded("2026-11-03T08:00:00.123|1"),encoded("2026-11-03T08:00:00.123456"),encoded("2026-11-03T08:00:00.123456|01")}) {
            assertThrows(IllegalArgumentException.class,()->CommentCursor.decode(value));
        }
    }
    private String encoded(String raw){return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));}
}
