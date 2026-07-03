package org.leo.dao.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SystemConfigMapper {

    @Select("SELECT config_value FROM system_configs WHERE config_key = #{key}")
    String findValueByKey(@Param("key") String key);

    @Insert("""
            INSERT INTO system_configs (
                config_key,
                config_value,
                config_type,
                description,
                create_time,
                update_time
            ) VALUES (
                #{key},
                #{value},
                #{type},
                #{description},
                datetime('now'),
                datetime('now')
            )
            ON CONFLICT(config_key) DO UPDATE SET
                config_value = excluded.config_value,
                config_type = excluded.config_type,
                description = excluded.description,
                update_time = datetime('now')
            """)
    int upsert(@Param("key") String key,
               @Param("value") String value,
               @Param("type") String type,
               @Param("description") String description);
}
