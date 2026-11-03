package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.BlogComments;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface BlogCommentsMapper extends BaseMapper<BlogComments> {

    @Insert("INSERT INTO tb_blog_comments(user_id,blog_id,parent_id,answer_id,content,liked,status,deleted,request_id,reply_count) "
            + "VALUES(#{userId},#{blogId},#{parentId},#{answerId},#{content},0,0,0,#{requestId},0)")
    @Options(useGeneratedKeys=true,keyProperty="id")
    int insertComment(BlogComments comment);
    @Select("SELECT * FROM tb_blog_comments WHERE id=#{id} FOR UPDATE")
    BlogComments lockById(long id);
    @Select("SELECT * FROM tb_blog_comments WHERE user_id=#{userId} AND request_id=#{requestId}")
    BlogComments byRequest(@Param("userId") long userId,@Param("requestId") String requestId);
    @Select("SELECT * FROM tb_blog_comments WHERE user_id=#{userId} AND request_id=#{requestId} FOR UPDATE")
    BlogComments lockRequest(@Param("userId") long userId,@Param("requestId") String requestId);
    @Update("UPDATE tb_blog_comments SET deleted=1 WHERE id=#{id} AND user_id=#{userId} AND deleted=0")
    int markDeleted(@Param("id") long id,@Param("userId") long userId);
    @Update("UPDATE tb_blog_comments SET reply_count=reply_count+#{delta} WHERE id=#{id} AND parent_id=0 "
            + "AND ((#{delta}=1 AND reply_count<4294967295) OR (#{delta}=-1 AND reply_count>0))")
    int adjustReplies(@Param("id") long id,@Param("delta") int delta);
    @Select({"<script>SELECT * FROM tb_blog_comments WHERE blog_id=#{blogId} AND parent_id=0 ",
            "AND (deleted=0 OR reply_count>0) ",
            "<if test='time != null'>AND (create_time &lt; #{time} OR (create_time=#{time} AND id &lt; #{cursorId})) </if>",
            "ORDER BY create_time DESC,id DESC LIMIT #{limit}</script>"})
    List<BlogComments> roots(@Param("blogId") long blogId,@Param("time") LocalDateTime time,
                            @Param("cursorId") Long cursorId,@Param("limit") int limit);
    @Select({"<script>SELECT * FROM tb_blog_comments WHERE parent_id=#{rootId} AND blog_id=#{blogId} ",
            "<if test='time != null'>AND (create_time &lt; #{time} OR (create_time=#{time} AND id &lt; #{cursorId})) </if>",
            "ORDER BY create_time DESC,id DESC LIMIT #{limit}</script>"})
    List<BlogComments> replies(@Param("rootId") long rootId,@Param("blogId") long blogId,
                              @Param("time") LocalDateTime time,@Param("cursorId") Long cursorId,@Param("limit") int limit);

}
