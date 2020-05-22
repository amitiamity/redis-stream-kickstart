package com.redisstream.kickstart.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.websocket.server.PathParam;

@RestController
@Slf4j
public class WelcomeController {

    @GetMapping("/{name}")
    public String welcome(@PathParam("name") String name) {
        return "Hello " + name;
    }
}
