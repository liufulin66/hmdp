package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.Follow;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface FollowMapper extends BaseMapper<Follow> {

    @Insert("INSERT INTO tb_follow(user_id,follow_user_id) VALUES(#{userId},#{followUserId})")
    int insertRelation(@Param("userId") long userId, @Param("followUserId") long followUserId);

    @Delete("DELETE FROM tb_follow WHERE user_id=#{userId} AND follow_user_id=#{followUserId}")
    int deleteRelation(@Param("userId") long userId, @Param("followUserId") long followUserId);

    @Select("SELECT EXISTS(SELECT 1 FROM tb_follow WHERE user_id=#{userId} AND follow_user_id=#{followUserId})")
    boolean existsRelation(@Param("userId") long userId, @Param("followUserId") long followUserId);

    @Select("SELECT a.follow_user_id FROM tb_follow a "
            + "JOIN tb_follow b ON b.user_id=#{followUserId} AND b.follow_user_id=a.follow_user_id "
            + "JOIN tb_user u ON u.id=a.follow_user_id "
            + "WHERE a.user_id=#{userId} ORDER BY a.follow_user_id ASC LIMIT #{limit} OFFSET #{offset}")
    List<Long> selectCommonUserIds(@Param("userId") long userId,
                                  @Param("followUserId") long followUserId,
                                  @Param("offset") int offset, @Param("limit") int limit);

    // 冲突时核对实际索引定义，兼容已有的等价索引名称，不把其他唯一键冲突当成功。
    @Select("SELECT COUNT(*) FROM (SELECT INDEX_NAME FROM information_schema.STATISTICS "
            + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tb_follow' AND INDEX_NAME=#{indexName} "
            + "GROUP BY INDEX_NAME HAVING MAX(NON_UNIQUE)=0 AND COUNT(*)=2 "
            + "AND GROUP_CONCAT(COLUMN_NAME ORDER BY COLUMN_NAME SEPARATOR ',')='follow_user_id,user_id' "
            + "AND SUM(SUB_PART IS NOT NULL)=0) matching_indexes")
    int isRelationUniqueIndex(@Param("indexName") String indexName);
}
