package com.dast.back.Controller;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@CrossOrigin
@RestController
@RequestMapping("/api/webhook")
public class WebHookController {


    @RequestMapping("/test")
    public String test() {
        return "WebHookController";
    }
}
