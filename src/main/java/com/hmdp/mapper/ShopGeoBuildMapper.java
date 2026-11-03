package com.hmdp.mapper;
import com.hmdp.entity.ShopGeoBuild;
import org.apache.ibatis.annotations.*;
public interface ShopGeoBuildMapper {
    @Insert("INSERT IGNORE INTO tb_shop_geo_build(id,status) VALUES(1,'IDLE')")
    int ensure();
    @Select("SELECT * FROM tb_shop_geo_build WHERE id=1")
    ShopGeoBuild find();
    @Update("UPDATE tb_shop_geo_build SET status='PENDING',requested_at=#{now},last_error=NULL "
            + "WHERE id=1 AND status IN ('IDLE','SUCCESS','FAILED')")
    int queue(long now);
    @Update("UPDATE tb_shop_geo_build SET status='RUNNING',claim_token=#{token},lease_until=#{until},last_error=NULL "
            + "WHERE id=1 AND (status='PENDING' OR (status='RUNNING' AND lease_until<=#{now}))")
    int claim(@Param("token") String token, @Param("now") long now, @Param("until") long until);
    @Update("UPDATE tb_shop_geo_build SET lease_until=#{until} WHERE id=1 AND status='RUNNING' "
            + "AND claim_token=#{token} AND lease_until>#{now}")
    int renew(@Param("token") String token, @Param("now") long now, @Param("until") long until);
    @Update("UPDATE tb_shop_geo_build SET status=#{status},lease_until=0,claim_token=NULL,last_error=#{error}, "
            + "last_successful_generation=IF(#{status}='SUCCESS',#{token},last_successful_generation) "
            + "WHERE id=1 AND status='RUNNING' AND claim_token=#{token}")
    int finish(@Param("token") String token, @Param("status") String status, @Param("error") String error);
}
