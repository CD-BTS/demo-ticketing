package com.bts.demoticketing.integration.bts;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import com.bts.demoticketing.user.entity.DemoUser;

@Component
public class BtsClient {
    private final BtsSettings settings;
    private final JsonMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    public BtsClient(BtsSettings settings,JsonMapper json){this.settings=settings;this.json=json;}
    public static class DeliveryFailure extends RuntimeException {
        public final boolean retryable;
        public final String code;
        public DeliveryFailure(String code,boolean retryable){super("BTS delivery failed: "+code);this.code=code;this.retryable=retryable;}
    }
    public String register(DemoUser user){
        if(!settings.serviceId.equals(user.serviceId()))throw new DeliveryFailure("SERVICE_MAPPING_MISMATCH",false);
        Map<?,?> data=post("/api/v1/users",json.writeValueAsString(Map.of(
                "serviceId",user.serviceId(),"externalUserId",user.externalId(),"accountStatus","ACTIVE")));
        String id=String.valueOf(data.get("userId"));
        try{UUID.fromString(id);}catch(IllegalArgumentException e){throw new DeliveryFailure("INVALID_BTS_RESPONSE",false);}
        if(!user.serviceId().equals(data.get("serviceId"))||!user.externalId().equals(data.get("externalUserId")))
            throw new DeliveryFailure("INVALID_BTS_RESPONSE",false);
        return id;
    }
    public long send(String payload){
        Map<?,?> data=post("/api/v1/events",payload);
        Map<?,?> sent=json.readValue(payload,Map.class);
        for(String key:List.of("serviceId","externalEventId","userId","eventType","bookingId")){
            if(!Objects.equals(sent.get(key),data.get(key)))throw new DeliveryFailure("INVALID_BTS_RESPONSE",false);
        }
        try {
            long id=new BigInteger(String.valueOf(data.get("eventId"))).longValueExact();
            if(id<=0)throw new ArithmeticException();
            return id;
        }catch(RuntimeException e){throw new DeliveryFailure("INVALID_BTS_RESPONSE",false);}
    }
    public PolicyDecisionClient.Action policy(String uuid){
        try{UUID.fromString(uuid);}catch(RuntimeException e){throw new DeliveryFailure("INVALID_BTS_USER",false);}
        Map<?,?> data=post("/api/v1/users/"+uuid+"/policy-decisions",json.writeValueAsString(Map.of("context","QUEUE_JOIN")));
        try{return PolicyDecisionClient.Action.valueOf(String.valueOf(data.get("action")));}
        catch(RuntimeException e){throw new DeliveryFailure("INVALID_BTS_POLICY",false);}
    }
    private Map<?,?> post(String path,String payload){
        if(!settings.enabled)throw new DeliveryFailure("BTS_DISABLED",true);
        HttpRequest request=HttpRequest.newBuilder(URI.create(settings.baseUrl.replaceAll("/+$","")+path))
                .timeout(Duration.ofSeconds(3)).header("Content-Type","application/json")
                .header("X-API-Key",settings.apiKey).POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        HttpResponse<String> response;
        try{response=http.send(request,HttpResponse.BodyHandlers.ofString());}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new DeliveryFailure("BTS_INTERRUPTED",true);}
        catch(IOException e){throw new DeliveryFailure("BTS_NETWORK",true);}
        Map<?,?> body;
        try{body=json.readValue(response.body(),Map.class);}
        catch(RuntimeException e){throw new DeliveryFailure("INVALID_BTS_RESPONSE",response.statusCode()>=500);}
        if(body==null)throw new DeliveryFailure("INVALID_BTS_RESPONSE",response.statusCode()>=500);
        String code=String.valueOf(body.get("code"));
        if(!code.matches("[A-Z0-9_]{1,64}"))code="INVALID_BTS_RESPONSE";
        int status=response.statusCode();
        if((status!=200&&status!=201)||!Boolean.TRUE.equals(body.get("success")))
            throw new DeliveryFailure(code,status>=500);
        if(!code.equals(status==201?"COMMON_201":"COMMON_200")||!(body.get("data") instanceof Map<?,?> data))
            throw new DeliveryFailure("INVALID_BTS_RESPONSE",false);
        return data;
    }
}
