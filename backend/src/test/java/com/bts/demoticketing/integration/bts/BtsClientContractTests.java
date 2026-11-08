package com.bts.demoticketing.integration.bts;

import java.io.IOException;
import java.net.*;
import java.util.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.type.TypeReference;
import com.bts.demoticketing.user.entity.DemoUser;
import static org.junit.jupiter.api.Assertions.*;

class BtsClientContractTests {
    private final JsonMapper json=JsonMapper.builder().build();
    @Test void attemptEventDoesNotNeedAFabricatedBookingIdAndPolicyUsesServerKey()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        String uuid=UUID.randomUUID().toString();
        server.createContext("/api/v1/events",e->{
            Map<String,Object> data=new LinkedHashMap<>(json.readValue(e.getRequestBody().readAllBytes(),new TypeReference<Map<String,Object>>() {}));
            assertFalse(data.containsKey("bookingId"));data.put("eventId",7);
            byte[] body=json.writeValueAsBytes(Map.of("success",true,"code","COMMON_201","data",data));
            e.sendResponseHeaders(201,body.length);e.getResponseBody().write(body);e.close();
        });
        server.createContext("/api/v1/users/"+uuid+"/policy-decisions",e->{
            assertEquals("test-only",e.getRequestHeaders().getFirst("X-API-Key"));
            assertEquals("QUEUE_JOIN",json.readValue(e.getRequestBody().readAllBytes(),Map.class).get("context"));
            byte[] body=json.writeValueAsBytes(Map.of("success",true,"code","COMMON_200","data",Map.of("action","REQUIRE_CAPTCHA")));
            e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();
        });
        server.start();
        try{
            var client=new BtsClient(new BtsSettings(true,"http://127.0.0.1:"+server.getAddress().getPort(),"service","test-only"),json);
            assertEquals(7,client.send(json.writeValueAsString(Map.of("serviceId","service","externalEventId","e1","userId",uuid,"eventType","BOOKING_ATTEMPT"))));
            assertEquals(PolicyDecisionClient.Action.REQUIRE_CAPTCHA,client.policy(uuid));
        }finally{server.stop(0);}
    }

    @Test void disabledDeliveryNeverUsesAnEmptyKey(){
        var client=new BtsClient(new BtsSettings(false,"","service",""),json);
        var error=assertThrows(BtsClient.DeliveryFailure.class,()->client.send("{}"));
        assertEquals("BTS_DISABLED",error.code);assertTrue(error.retryable);
    }
    @Test void remotePlainHttpAndMissingKeyFailFast(){
        assertThrows(IllegalArgumentException.class,()->new BtsSettings(true,"http://example.com","service","key"));
        assertThrows(IllegalArgumentException.class,()->new BtsSettings(true,"http://127.0.0.1:9999","service",""));
    }
    @Test void connectionRefusedIsRetryable()throws IOException{
        int port;try(var socket=new java.net.ServerSocket(0,0,InetAddress.getByName("127.0.0.1"))){port=socket.getLocalPort();}
        var client=new BtsClient(new BtsSettings(true,"http://127.0.0.1:"+port,"service","test-only"),json);
        var error=assertThrows(BtsClient.DeliveryFailure.class,()->client.send("{}"));
        assertEquals("BTS_NETWORK",error.code);assertTrue(error.retryable);
    }
    @Test void registrationUsesServiceKeyAndCamelCaseContract()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        List<Map<?,?>> requests=new ArrayList<>();List<String> keys=new ArrayList<>();
        String uuid=UUID.randomUUID().toString();
        server.createContext("/api/v1/users",e->{
            requests.add(json.readValue(e.getRequestBody().readAllBytes(),Map.class));
            keys.add(e.getRequestHeaders().getFirst("X-API-Key"));
            byte[] body=json.writeValueAsBytes(Map.of("success",true,"code","COMMON_200","message","ok","data",
                    Map.of("userId",uuid,"serviceId","service","externalUserId","external")));
            e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();
        });
        server.start();
        try{
            var client=new BtsClient(new BtsSettings(true,"http://127.0.0.1:"+server.getAddress().getPort(),"service","test-only"),json);
            assertEquals(uuid,client.register(new DemoUser(1,"local","service","external",null)));
            assertEquals(List.of("test-only"),keys);
            assertEquals(Set.of("serviceId","externalUserId","accountStatus"),requests.get(0).keySet());
            assertEquals("ACTIVE",requests.get(0).get("accountStatus"));
        }finally{server.stop(0);}
    }
}
