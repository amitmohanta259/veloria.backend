package com.app.master.service.core.service;

import com.app.master.service.core.response.ResponseCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class MessageService {

    @Autowired
    private MessageSource messageSource;

    public String getMessage(final ResponseCode code, final String... params) {
        return this.messageSource.getMessage(code.name(), params, Locale.ENGLISH);
    }

} 