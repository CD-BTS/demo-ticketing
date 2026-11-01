package com.bts.demoticketing.integration.bts;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BtsSettings {
    public final boolean enabled;
    public final String baseUrl,serviceId,apiKey;
    public BtsSettings(@Value("${bts.enabled:false}") boolean enabled,
            @Value("${bts.base-url:}") String baseUrl,@Value("${bts.service-id}") String serviceId,
            @Value("${bts.api-key:}") String apiKey) {
        this.enabled=enabled; this.baseUrl=baseUrl; this.serviceId=serviceId; this.apiKey=apiKey;
        if(serviceId.isBlank()||serviceId.length()>50)throw new IllegalArgumentException("Invalid BTS service ID");
        if(enabled){
            if(apiKey.isBlank()||apiKey.indexOf('\r')>=0||apiKey.indexOf('\n')>=0)throw new IllegalArgumentException("Enabled BTS delivery requires a valid service key");
            URI uri=URI.create(baseUrl);
            boolean local="localhost".equals(uri.getHost())||"127.0.0.1".equals(uri.getHost())||"::1".equals(uri.getHost());
            if(uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||
                    !(uri.getScheme().equals("https")||(local&&uri.getScheme().equals("http"))))
                throw new IllegalArgumentException("BTS URL must use HTTPS except on loopback");
        }
    }
}
