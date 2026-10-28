package com.vtrace.business.auth;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {
    @Select("SELECT id FROM users WHERE id = #{userId} AND is_active = TRUE")
    Integer findActiveUser(int userId);
}
