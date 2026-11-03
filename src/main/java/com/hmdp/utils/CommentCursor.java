package com.hmdp.utils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Base64;

/** 精确保留 MySQL TIMESTAMP(6)，不经由浏览器时间戳或毫秒截断。 */
public record CommentCursor(LocalDateTime time, long id) {
    private static final DateTimeFormatter FORMAT=DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS").withResolverStyle(ResolverStyle.STRICT);
    public String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((FORMAT.format(time)+"|"+id).getBytes(StandardCharsets.UTF_8));
    }
    public static CommentCursor decode(String value) {
        if (value==null) return null;
        try {
            if (value.isEmpty() || value.length()>120 || !value.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            String raw=new String(Base64.getUrlDecoder().decode(value),StandardCharsets.UTF_8);
            String[] parts=raw.split("\\|",-1);
            if (parts.length!=2 || !parts[1].matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
            LocalDateTime time=LocalDateTime.parse(parts[0],FORMAT); long id=Long.parseLong(parts[1]);
            if (id<=0 || time.getYear()<1970 || time.getYear()>2038) throw new IllegalArgumentException();
            CommentCursor cursor=new CommentCursor(time,id);
            if (!cursor.encode().equals(value)) throw new IllegalArgumentException();
            return cursor;
        } catch (RuntimeException e) { throw new IllegalArgumentException("评论游标无效或不完整"); }
    }
}
