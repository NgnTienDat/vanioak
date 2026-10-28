package com.h.vanioak.api.alert;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import com.h.vanioak.BackendApplication;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes=BackendApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(locations="classpath:application.properties",properties={"server.address=127.0.0.1","spring.rabbitmq.dynamic=false",
        "spring.flyway.enabled=false","vanioak.processing.consumer-enabled=false","vanioak.realtime.enabled=false",
        "vanioak.alert.consumer-enabled=false","vanioak.alert.resolver-enabled=false","logging.level.root=INFO"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class IncidentListNativeIT {
    static final Path ROOT=Path.of("D:/Web System Development/vanioak");
    static final String RUN=System.getenv("INCIDENT_LIST_NATIVE_RUN"),ADMIN="incident-list-admin-"+RUN,PASSWORD=UUID.randomUUID().toString();
    static final String APP="31801983-8baa-4b0a-ba77-8df1f212668b",ENV="a822d0a6-2b02-4f35-bcb3-ce9ca81613ca",INCIDENT="07620f41-b08e-4987-b8d7-1fa04903a37c";
    static String env(String key) {return Objects.requireNonNull(System.getenv(key));}
    static void markers() throws Exception {
        assertArrayEquals("ALERT_ARMED_REVIEW_REQUIRED\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),Files.readAllBytes(ROOT.resolve(".local/alert-recovery.fence")));
        assertArrayEquals("REALTIME_ARMED_REVIEW_REQUIRED\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),Files.readAllBytes(ROOT.resolve(".local/realtime-native-happy-20261026/realtime.fence")));
    }
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry p) throws Exception {
        if(!"true".equals(env("INCIDENT_LIST_NATIVE_APPROVED")) || !"jdbc:postgresql://127.0.0.1:25432/processing_test".equals(env("PROCESSING_POSTGRES_TEST_URL"))
                || !"127.0.0.1".equals(env("PROCESSING_RABBITMQ_TEST_HOST")) || !"25672".equals(env("PROCESSING_RABBITMQ_TEST_PORT"))
                || !"processing-test".equals(env("PROCESSING_RABBITMQ_TEST_VHOST")) || !"26379".equals(env("PROCESSING_REDIS_TEST_PORT")))
            throw new IllegalStateException("Approved API-only isolated fixture required");
        UUID.fromString(RUN);markers();var mapper=new ObjectMapper();Path directory=Path.of(env("INCIDENT_LIST_NATIVE_EVIDENCE"));
        var pre=mapper.readTree(Files.readAllBytes(directory.resolve("preflight.json")));var claim=mapper.readTree(Files.readAllBytes(directory.resolve("one-shot.json")));
        if(!pre.get("pass").asBoolean() || !RUN.equals(pre.get("run_id").asString()) || !RUN.equals(claim.get("run_id").asString())
                || !"IncidentListNativeIT#existingIncidentWithNullableFilters".equals(claim.get("selector").asString()))throw new IllegalStateException("One-shot/preflight binding required");
        for(String key:List.of("URL","USERNAME","PASSWORD")){p.add("spring.datasource."+key.toLowerCase(Locale.ROOT),() -> env("PROCESSING_POSTGRES_TEST_"+key));p.add("vanioak.clickhouse."+key.toLowerCase(Locale.ROOT),() -> env("PROCESSING_CLICKHOUSE_TEST_"+key));}
        for(String key:List.of("HOST","PORT","DATABASE"))p.add("spring.data.redis."+key.toLowerCase(Locale.ROOT),() -> env("PROCESSING_REDIS_TEST_"+key));
        for(String key:List.of("HOST","PORT","VHOST","USERNAME","PASSWORD"))p.add("spring.rabbitmq."+(key.equals("VHOST")?"virtual-host":key.toLowerCase(Locale.ROOT)),() -> env("PROCESSING_RABBITMQ_TEST_"+key));
        p.add("spring.jpa.hibernate.ddl-auto",() -> "validate");p.add("vanioak.identity.bootstrap-admin.enabled",() -> "true");
        p.add("vanioak.identity.bootstrap-admin.username",() -> ADMIN);p.add("vanioak.identity.bootstrap-admin.password",() -> PASSWORD);
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired Environment configuration;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    final Map<String,Object> state=new LinkedHashMap<>();
    String access,refresh;
    void save(String name,Object value) throws Exception {Files.write(Path.of(env("INCIDENT_LIST_NATIVE_EVIDENCE")).resolve(name),json.writeValueAsBytes(value));}
    void phase(String name) throws Exception {state.put("run_id",RUN);state.put("port",port);state.put("phase",name);save("scenario-state.json",state);}
    HttpResponse<String> request(String method,String path,Object body) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).timeout(Duration.ofSeconds(5));
        if(access!=null)b.header("Authorization","Bearer "+access);
        if(body!=null)b.header("Content-Type","application/json");
        return http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    void verifyList(String path,int index) throws Exception {
        var response=request("GET",path,null);var root=json.readTree(response.body());
        save("response-"+index+".json",Map.of("request_path",path,"status",response.statusCode(),"body",root));
        assertEquals(200,response.statusCode());assertEquals(3,root.size());assertTrue(root.get("success").asBoolean());assertTrue(root.get("message").isString());
        var data=root.get("data");assertEquals(1,data.get("items").size());assertTrue(data.get("next_cursor").isNull());var i=data.get("items").get(0);
        assertEquals(INCIDENT,i.get("id").asString());assertEquals("OPEN",i.get("status").asString());assertEquals(3,i.get("error_count").asInt());
        assertEquals(APP,i.get("application").get("id").asString());assertEquals(ENV,i.get("environment").get("id").asString());assertFalse(i.has("events"));
    }
    @Test @Timeout(45) void existingIncidentWithNullableFilters() throws Exception {
        phase("api_ready");
        for(String key:List.of("spring.rabbitmq.dynamic","spring.flyway.enabled","vanioak.processing.consumer-enabled","vanioak.realtime.enabled","vanioak.alert.consumer-enabled","vanioak.alert.resolver-enabled"))
            assertEquals("false",configuration.getProperty(key));
        var login=request("POST","/auth/login",Map.of("username",ADMIN,"password",PASSWORD));assertEquals(200,login.statusCode());
        var data=json.readTree(login.body()).get("data");access=data.get("access_token").asString();refresh=data.get("refresh_token").asString();state.put("user_id",data.get("user").get("id").asString());phase("authenticated");
        String path="/incidents?application_id="+APP+"&environment_id="+ENV;
        verifyList(path,0);
        Instant last=Instant.parse(env("INCIDENT_LIST_LAST_SEEN"));
        verifyList(path+"&from="+last+"&status=OPEN&level=ERROR",1);
        verifyList(path+"&to="+last.plusSeconds(1)+"&status=OPEN&level=ERROR",2);
        markers();state.put("incident_get_requests",3);state.put("outcome","PASS");phase("verified");
    }
    @AfterEach @Timeout(20) void cleanup() throws Exception {
        try {if(refresh!=null){assertEquals(200,request("POST","/auth/logout",Map.of("refresh_token",refresh)).statusCode());state.put("sessions_logged_out",1);}phase("cleanup_complete");}
        finally {http.shutdownNow();}
    }
}
