package com.hmdp.mapper;

import com.hmdp.entity.FeedDeliveryTask;
import org.apache.ibatis.annotations.*;

import java.util.List;

public interface FeedDeliveryTaskMapper {
    // 与笔记 INSERT 使用同一事务。一次 SQL 固定发布时查询到的全部接收人。
    @Insert("INSERT INTO tb_feed_delivery_task(blog_id,user_id,published_at) "
            + "SELECT #{blogId},user_id,#{publishedAt} FROM tb_follow WHERE follow_user_id=#{authorId}")
    int createForFollowers(@Param("blogId") long blogId, @Param("authorId") long authorId,
                          @Param("publishedAt") long publishedAt);

    @Select("SELECT * FROM tb_feed_delivery_task WHERE blog_id=#{blogId} AND id>#{afterId} "
            + "AND status='PENDING' AND attempts=0 ORDER BY id LIMIT #{limit}")
    List<FeedDeliveryTask> selectNewForBlog(@Param("blogId") long blogId, @Param("afterId") long afterId,
                                            @Param("limit") int limit);

    @Select("SELECT * FROM tb_feed_delivery_task WHERE attempts<#{maxAttempts} AND "
            + "((status='PENDING' AND next_attempt_at<=#{now}) OR (status='PROCESSING' AND lease_until<=#{now})) "
            + "ORDER BY next_attempt_at,id LIMIT #{limit}")
    List<FeedDeliveryTask> selectDue(@Param("now") long now, @Param("maxAttempts") int maxAttempts,
                                    @Param("limit") int limit);

    @Update("UPDATE tb_feed_delivery_task SET status='PROCESSING',attempts=attempts+1,"
            + "claim_token=#{token},lease_until=#{leaseUntil} WHERE id=#{id} AND attempts=#{attempts} "
            + "AND attempts<#{maxAttempts} AND ((status='PENDING' AND next_attempt_at<=#{now}) "
            + "OR (status='PROCESSING' AND lease_until<=#{now}))")
    int claim(@Param("id") long id, @Param("attempts") int attempts, @Param("maxAttempts") int maxAttempts,
              @Param("token") String token, @Param("now") long now, @Param("leaseUntil") long leaseUntil);

    @Update("UPDATE tb_feed_delivery_task SET status='SUCCESS',claim_token=NULL,lease_until=0,last_error=NULL "
            + "WHERE id=#{id} AND status='PROCESSING' AND claim_token=#{token}")
    int succeed(@Param("id") long id, @Param("token") String token);

    @Update("UPDATE tb_feed_delivery_task SET status=#{status},next_attempt_at=#{nextAttemptAt},"
            + "claim_token=NULL,lease_until=0,last_error=#{error} "
            + "WHERE id=#{id} AND status='PROCESSING' AND claim_token=#{token}")
    int fail(@Param("id") long id, @Param("token") String token, @Param("status") String status,
             @Param("nextAttemptAt") long nextAttemptAt, @Param("error") String error);

    @Update("UPDATE tb_feed_delivery_task SET status='FAILED',claim_token=NULL,lease_until=0,"
            + "last_error='worker lease expired after maximum attempts' "
            + "WHERE attempts>=#{maxAttempts} AND ((status='PROCESSING' AND lease_until<=#{now}) "
            + "OR (status='PENDING' AND next_attempt_at<=#{now})) LIMIT #{limit}")
    int failExhausted(@Param("now") long now, @Param("maxAttempts") int maxAttempts, @Param("limit") int limit);
}
