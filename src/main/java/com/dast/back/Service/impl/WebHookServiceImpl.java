package com.dast.back.Service.impl;

import com.dast.back.Bean.WebHook;
import com.dast.back.Service.WebHookService;
import com.dast.back.mapper.WebHookMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class WebHookServiceImpl implements WebHookService {

    @Autowired
    private WebHookMapper webHookMapper;




    @Override
    public WebHook selectById(String id) {

        return webHookMapper.selectById(id);
    }
}
