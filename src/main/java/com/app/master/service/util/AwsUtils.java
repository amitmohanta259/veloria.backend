package com.app.master.service.util;

import com.app.master.service.core.service.AwsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
@Slf4j
@RequiredArgsConstructor
public class AwsUtils {

    private final AwsService awsService;

    public String getPreSignedUrlOrNull(String key) {
        if (key == null || key.isEmpty()) return null;
        try {
            return awsService.getViewablePreSignedUrl(key);
        } catch (IOException e) {
            log.error("Error generating pre-signed URL for key {}: {}", key, e.getMessage());
            return null;
        }
    }

}