package com.hmdp.mapper;
import com.hmdp.entity.ShopGeoTask;
import org.apache.ibatis.annotations.*;
import java.util.List;
public interface ShopGeoTaskMapper {
    @Insert("INSERT INTO tb_shop_geo_task(shop_id,version,type_id,x,y,next_attempt_at) "
            + "VALUES(#{shopId},#{version},#{typeId},#{x},#{y},#{nextAttemptAt})")
    @Options(useGeneratedKeys=true, keyProperty="id")
    int insertTask(ShopGeoTask task);
    @Select("SELECT * FROM tb_shop_geo_task WHERE id=#{id}")
    ShopGeoTask find(long id);
    @Select("SELECT * FROM tb_shop_geo_task WHERE attempts<#{maximum} AND "
            + "((status='PENDING' AND next_attempt_at<=#{now}) OR (status='PROCESSING' AND lease_until<=#{now})) "
            + "ORDER BY next_attempt_at,id LIMIT #{limit}")
    List<ShopGeoTask> due(@Param("now") long now, @Param("maximum") int maximum, @Param("limit") int limit);
    @Update("UPDATE tb_shop_geo_task SET status='PROCESSING',attempts=attempts+1,claim_token=#{token},"
            + "lease_until=#{until},next_attempt_at=#{until} WHERE id=#{id} AND attempts=#{attempts} AND attempts<#{maximum} "
            + "AND ((status='PENDING' AND next_attempt_at<=#{now}) OR (status='PROCESSING' AND lease_until<=#{now}))")
    int claim(@Param("id") long id, @Param("attempts") int attempts, @Param("maximum") int maximum,
              @Param("token") String token, @Param("now") long now, @Param("until") long until);
    @Update("UPDATE tb_shop_geo_task SET status='SUCCESS',lease_until=0,claim_token=NULL,last_error=NULL "
            + "WHERE id=#{id} AND status='PROCESSING' AND claim_token=#{token}")
    int succeed(@Param("id") long id, @Param("token") String token);
    @Update("UPDATE tb_shop_geo_task SET status=#{status},next_attempt_at=#{due},lease_until=0,claim_token=NULL,last_error=#{error} "
            + "WHERE id=#{id} AND status='PROCESSING' AND claim_token=#{token}")
    int fail(@Param("id") long id, @Param("token") String token, @Param("status") String status,
             @Param("due") long due, @Param("error") String error);
    @Update("UPDATE tb_shop_geo_task SET status='FAILED',lease_until=0,claim_token=NULL,last_error='maximum attempts exhausted' "
            + "WHERE attempts>=#{maximum} AND ((status='PROCESSING' AND lease_until<=#{now}) "
            + "OR (status='PENDING' AND next_attempt_at<=#{now})) LIMIT #{limit}")
    int exhaust(@Param("now") long now, @Param("maximum") int maximum, @Param("limit") int limit);
}
