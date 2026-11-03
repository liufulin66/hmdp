package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.Blog;
import org.apache.ibatis.annotations.*;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface BlogMapper extends BaseMapper<Blog> {

    @Select("SELECT * FROM tb_blog WHERE id=#{id} FOR UPDATE")
    Blog lockForComments(long id);

    @Update("UPDATE tb_blog SET comments=comments+#{delta},comment_version=comment_version+1 "
            + "WHERE id=#{id} AND ((#{delta}=-1 AND comments>0) OR #{delta}=0 "
            + "OR (#{delta}=1 AND comments<2147483647)) "
            + "AND comment_version<9223372036854775807")
    int adjustComments(@Param("id") long id,@Param("delta") int delta);

}
