package com.h.vanioak.modules.identity.internal.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;
import java.util.Objects;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import com.h.vanioak.BackendApplication;
import com.h.vanioak.modules.identity.api.ApplicationAccessFacade;
import com.h.vanioak.modules.identity.api.ErrorCode;
import com.h.vanioak.modules.identity.api.IdentityException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes=BackendApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(locations="classpath:application.properties",properties={"server.address=127.0.0.1","spring.rabbitmq.dynamic=false","vanioak.processing.consumer-enabled=false"})
class ApplicationAccessIT {
	static final String ADMIN="rbac-admin-"+UUID.randomUUID(), PASSWORD=UUID.randomUUID().toString();
	@DynamicPropertySource static void isolated(DynamicPropertyRegistry p) {
		if(!"true".equals(System.getenv("RBAC_TEST_SCOPE_APPROVED")) || !"jdbc:postgresql://127.0.0.1:25432/processing_test".equals(System.getenv("PROCESSING_POSTGRES_TEST_URL")) || !"127.0.0.1".equals(System.getenv("PROCESSING_REDIS_TEST_HOST")) || !"26379".equals(System.getenv("PROCESSING_REDIS_TEST_PORT")) || !"0".equals(System.getenv("PROCESSING_REDIS_TEST_DATABASE")))throw new IllegalStateException("Require explicit isolated RBAC fixture");
		for(String k:List.of("URL","USERNAME","PASSWORD"))p.add("spring.datasource."+k.toLowerCase(Locale.ROOT),()->Objects.requireNonNull(System.getenv("PROCESSING_POSTGRES_TEST_"+k)));
		for(String k:List.of("HOST","PORT","DATABASE"))p.add("spring.data.redis."+k.toLowerCase(Locale.ROOT),()->Objects.requireNonNull(System.getenv("PROCESSING_REDIS_TEST_"+k)));
		for(String k:List.of("URL","USERNAME","PASSWORD"))p.add("vanioak.clickhouse."+k.toLowerCase(Locale.ROOT),()->Objects.requireNonNull(System.getenv("PROCESSING_CLICKHOUSE_TEST_"+k)));
        for(String k:List.of("HOST","PORT","VHOST","USERNAME","PASSWORD"))p.add("spring.rabbitmq."+(k.equals("VHOST")?"virtual-host":k.toLowerCase(Locale.ROOT)),()->Objects.requireNonNull(System.getenv("PROCESSING_RABBITMQ_TEST_"+k)));
        p.add("spring.jpa.hibernate.ddl-auto",()->"validate");p.add("vanioak.identity.bootstrap-admin.enabled",()->"true");p.add("vanioak.identity.bootstrap-admin.username",()->ADMIN);p.add("vanioak.identity.bootstrap-admin.password",()->PASSWORD);
	}
	@LocalServerPort int port;
	@Autowired ObjectMapper json;
	@Autowired ApplicationAccessFacade access;
	@Autowired UserApplicationAccessRepository repository;
	private final List<Map<String,String>> sessions = new ArrayList<>();
	private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private HttpResponse<String> send(String method,String path,String token,Object body) throws Exception {
		var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).timeout(Duration.ofSeconds(8));
		if(token!=null)b.header("Authorization","Bearer "+token);
		if(body!=null)b.header("Content-Type","application/json");
		return client.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
	}
	private JsonNode call(String method,String path,String token,Object body,int status) throws Exception {
		var response=send(method,path,token,body);assertEquals(status,response.statusCode(),method+" "+path);return json.readTree(response.body()).get("data");
	}
	private String login(String name,String password)throws Exception {
		var data=call("POST","/auth/login",null,Map.of("username",name,"password",password),200);
		var session=Map.of("access_token",data.get("access_token").asString(),"refresh_token",data.get("refresh_token").asString());sessions.add(session);return session.get("access_token");
	}
	@org.junit.jupiter.api.AfterEach void closeSessions() throws Exception {
		try { for(var session:sessions)call("POST","/auth/logout",session.get("access_token"),Map.of("refresh_token",session.get("refresh_token")),200); }
		finally { client.close(); }
	}
	private Set<String> ids(JsonNode data){var ids=new HashSet<String>();for(var row:data.get("items"))ids.add(row.get("id").asString());return ids;}
	@Test @Timeout(90) void assignmentAndRevocationAreEnforcedWithSameJwt()throws Exception {
		String admin=login(ADMIN,PASSWORD);String password=UUID.randomUUID().toString(),name="rbac-e-"+UUID.randomUUID();
		String e=call("POST","/users",admin,Map.of("username",name,"password",password),201).get("id").asString();
		String otherName="rbac-other-"+UUID.randomUUID();
		String other=call("POST","/users",admin,Map.of("username",otherName,"password",password),201).get("id").asString();
		String otherToken=login(otherName,password);
		String token=login(name,password);var a=call("POST","/applications",admin,Map.of("name","rbac-a-"+UUID.randomUUID()),201);var b=call("POST","/applications",admin,Map.of("name","rbac-b-"+UUID.randomUUID()),201);String aid=a.get("id").asString(),bid=b.get("id").asString();String route="/applications/"+aid+"/engineers/"+e;
		assertTrue(ids(call("GET","/applications?user_id="+other+"&role=ADMIN",token,null,200)).isEmpty());
		call("POST",route,token,null,403);assertFalse(repository.existsByUserIdAndApplicationId(UUID.fromString(e),UUID.fromString(aid)));
		var assigned=call("POST",route,admin,null,200);assertEquals(e,assigned.get("user_id").asString());assertEquals(aid,assigned.get("application_id").asString());call("POST",route,admin,null,200);
		call("POST","/applications/"+bid+"/engineers/"+other,admin,null,200);assertEquals(Set.of(aid),ids(call("GET","/applications",token,null,200)));assertEquals(Set.of(bid),ids(call("GET","/applications",otherToken,null,200)));
		var page=call("GET","/applications?limit=1",token,null,200);assertEquals(Set.of(aid),ids(page));assertTrue(page.get("next_cursor").isNull());
		String foreignCursor=Base64.getUrlEncoder().withoutPadding().encodeToString(bid.getBytes(java.nio.charset.StandardCharsets.UTF_8));assertFalse(ids(call("GET","/applications?cursor="+foreignCursor,token,null,200)).contains(bid));
		for(var env:a.get("environments"))access.requireEnvironmentReadAccess(UUID.fromString(e),UUID.fromString(aid),UUID.fromString(env.get("id").asString()));
		assertEquals(ErrorCode.ACCESS_DENIED,assertThrows(IdentityException.class,()->access.requireEnvironmentReadAccess(UUID.fromString(e),UUID.fromString(aid),UUID.fromString(b.get("environments").get(0).get("id").asString()))).getErrorCode());
		call("PATCH","/applications/"+aid,admin,Map.of("status","DISABLED"),200);assertEquals(Set.of(aid),ids(call("GET","/applications?status=DISABLED",token,null,200)));call("POST",route,admin,null,403);
		call("DELETE",route,admin,null,200);call("DELETE",route,admin,null,200);assertTrue(ids(call("GET","/applications",token,null,200)).isEmpty());
		call("PATCH","/applications/"+aid,admin,Map.of("status","ACTIVE"),200);call("POST",route,admin,null,200);
		call("PATCH","/users/"+e,admin,Map.of("status","DISABLED"),200);call("GET","/applications",token,null,403);call("POST",route,admin,null,403);assertTrue(repository.existsByUserIdAndApplicationId(UUID.fromString(e),UUID.fromString(aid)));call("DELETE",route,admin,null,200);
		call("PATCH","/users/"+e,admin,Map.of("status","ACTIVE"),200);assertTrue(ids(call("GET","/applications",token,null,200)).isEmpty());
		call("POST","/applications",token,Map.of("name","forbidden"),403);call("PATCH","/applications/"+aid,token,Map.of("status","DISABLED"),403);call("DELETE","/applications/"+bid+"/engineers/"+other,token,null,403);
		call("GET","/applications/"+aid+"/environments/"+a.get("environments").get(0).get("id").asString()+"/api-keys",token,null,403);
		call("POST","/applications/"+aid+"/engineers/"+UUID.randomUUID(),admin,null,404);call("POST","/applications/"+UUID.randomUUID()+"/engineers/"+e,admin,null,404);
		var bad=send("POST","/applications/not-a-uuid/engineers/"+e,admin,null);assertTrue(Set.of(400,500).contains(bad.statusCode()));assertFalse(repository.existsByUserIdAndApplicationId(UUID.fromString(e),UUID.fromString(aid)));assertFalse(json.readTree(bad.body()).get("success").asBoolean());
		call("GET","/applications",null,null,401);assertTrue(ids(call("GET","/applications",admin,null,200)).containsAll(Set.of(aid,bid)));
		call("DELETE","/applications/"+bid+"/engineers/"+other,admin,null,200);
		var evidence=Path.of(Objects.requireNonNull(System.getenv("RBAC_EVIDENCE_DIR")));Files.writeString(evidence.resolve("rbac-http-result.json"),json.writeValueAsString(Map.of("status","PASS","invalid_uuid_status",bad.statusCode(),"application_a",aid,"application_b",bid,"engineer",e,"port",port)));
	}
}
