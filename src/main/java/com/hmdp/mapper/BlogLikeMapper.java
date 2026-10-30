package com.hmdp.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;
import com.hmdp.service.impl.BlogLikeChange;

public interface BlogLikeMapper {
    @Select("SELECT user_id FROM tb_blog_like WHERE blog_id=#{blogId} AND liked=1")
    List<Long> selectLikedUsers(long blogId);

    @Select("SELECT COUNT(*) FROM tb_blog_like WHERE blog_id=#{blogId} AND user_id=#{userId} AND liked=1")
    int isLiked(@Param("blogId") long blogId, @Param("userId") long userId);

    @Select("SELECT version FROM tb_blog_like_sync WHERE blog_id=#{blogId}")
    Long selectVersion(long blogId);

    @Insert("INSERT IGNORE INTO tb_blog_like_sync(blog_id,version) VALUES(#{blogId},0)")
    void ensureSyncRow(long blogId);

    @Select("SELECT version FROM tb_blog_like_sync WHERE blog_id=#{blogId} FOR UPDATE")
    long lockVersion(long blogId);

    @Select({"<script>",
            "SELECT user_id,liked,version FROM tb_blog_like WHERE blog_id=#{blogId} AND user_id IN",
            "<foreach collection='changes' item='change' open='(' separator=',' close=')'>",
            "#{change.userId}", "</foreach>", "</script>"})
    List<BlogLikeChange> selectStates(@Param("blogId") long blogId,
                                    @Param("changes") List<BlogLikeChange> changes);

    @Insert({"<script>",
            "INSERT INTO tb_blog_like(blog_id,user_id,liked,version) VALUES",
            "<foreach collection='changes' item='change' separator=','>",
            "(#{blogId},#{change.userId},#{change.liked},#{change.version})",
            "</foreach>",
            "ON DUPLICATE KEY UPDATE liked=IF(version &lt; VALUES(version),VALUES(liked),liked),",
            "version=GREATEST(version,VALUES(version))",
            "</script>"})
    void upsertStates(@Param("blogId") long blogId, @Param("changes") List<BlogLikeChange> changes);

    @Update("UPDATE tb_blog SET liked=CAST(COALESCE(liked,0) AS SIGNED)+#{delta} WHERE id=#{blogId} "
            + "AND CAST(COALESCE(liked,0) AS SIGNED)+#{delta} BETWEEN 0 AND 2147483647")
    int incrementTotal(@Param("blogId") long blogId, @Param("delta") int delta);

    @Update("UPDATE tb_blog_like_sync SET version=#{version} WHERE blog_id=#{blogId}")
    void updateVersion(@Param("blogId") long blogId, @Param("version") long version);
}
