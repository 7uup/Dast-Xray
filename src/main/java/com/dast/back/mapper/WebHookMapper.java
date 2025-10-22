package com.dast.back.mapper;

import com.dast.back.Bean.WebHook;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WebHookMapper {
//    Integer insert(WebHook webHook);
//    WebHook selectById(Integer id);
//    Integer update(WebHook webHook);
//    Integer delete(Integer id);
    WebHook selectById(@Param("id") String id);
}
