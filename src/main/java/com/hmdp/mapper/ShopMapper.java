package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.Shop;
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
public interface ShopMapper extends BaseMapper<Shop> {

    @Select("SELECT * FROM tb_shop WHERE id=#{id} FOR UPDATE")
    Shop lockById(long id);

    @Update("UPDATE tb_shop SET geo_version=geo_version+1 WHERE id=#{id} AND geo_version<9223372036854775807")
    int bumpGeoVersion(long id);

    @Select("SELECT * FROM tb_shop WHERE type_id=#{typeId} ORDER BY id LIMIT #{limit} OFFSET #{offset}")
    List<Shop> byType(@Param("typeId") long typeId, @Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT * FROM tb_shop WHERE id>#{afterId} ORDER BY id LIMIT #{limit}")
    List<Shop> scanGeo(@Param("afterId") long afterId, @Param("limit") int limit);

}
