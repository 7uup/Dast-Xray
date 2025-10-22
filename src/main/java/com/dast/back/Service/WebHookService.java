package com.dast.back.Service;

import com.dast.back.Bean.WebHook;



public interface WebHookService {
//    Integer insert(WebHook webHook);
//    WebHook selectById(Integer id);
//    Integer update(WebHook webHook);
//    Integer delete(Integer id);
    WebHook selectById(String id);
}
