package com.app.master.service.core.config;

import com.app.master.service.core.constant.Constant;
import org.apache.http.entity.ContentType;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Configuration
public class FeignConfig {

    @Bean
    public RequestInterceptor requestInterceptor() {
        return requestTemplate -> {
            requestTemplate.header(Constant.AUTHORIZATION, ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest().getHeader(Constant.AUTHORIZATION));
            requestTemplate.header(Constant.ACCEPT, ContentType.APPLICATION_JSON.getMimeType());
        };
    }

}