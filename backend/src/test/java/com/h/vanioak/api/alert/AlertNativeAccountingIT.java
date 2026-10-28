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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.h.vanioak.BackendApplication;
import com.h.vanioak.modules.alert.internal.consumer.AlertConsumerLifecycle;
import com.h.vanioak.modules.alert.internal.consumer.AlertLogConsumer;
import com.rabbitmq.client.Channel;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

@SpringBootTest(classes=BackendApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(locations="classpath:application.properties",properties={"server.address=127.0.0.1","spring.rabbitmq.dynamic=false",
		"spring.flyway.enabled=false","vanioak.processing.consumer-enabled=false","vanioak.realtime.enabled=false","vanioak.alert.resolver-enabled=false",
		"logging.level.root=INFO","logging.level.org.springframework=INFO","logging.level.org.hibernate=INFO","logging.level.com.h.vanioak=INFO"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class AlertNativeAccountingIT {
	static final Path FENCE=Path.of("D:/Web System Development/vanioak").resolve(".local/alert-recovery.fence");
	static final String RUN=System.getenv("ALERT_NATIVE_IT_RUN"),ADMIN="alert-admin-"+RUN,PASSWORD=UUID.randomUUID().toString();
	static String env(String key) {return Objects.requireNonNull(System.getenv(key));}
	@DynamicPropertySource static void isolated(DynamicPropertyRegistry p) throws Exception {
		if(!"true".equals(System.getenv("ALERT_NATIVE_IT_APPROVED")) || !FENCE.equals(Path.of(env("ALERT_NATIVE_IT_FENCE")))
				|| Files.exists(FENCE) || !Files.isDirectory(FENCE.getParent()) || !"jdbc:postgresql://127.0.0.1:25432/processing_test".equals(env("PROCESSING_POSTGRES_TEST_URL"))
				|| !"127.0.0.1".equals(env("PROCESSING_RABBITMQ_TEST_HOST")) || !"25672".equals(env("PROCESSING_RABBITMQ_TEST_PORT"))
				|| !"processing-test".equals(env("PROCESSING_RABBITMQ_TEST_VHOST")) || !"26379".equals(env("PROCESSING_REDIS_TEST_PORT")))
			throw new IllegalStateException("Approved isolated Alert fixture/fence required");
		UUID.fromString(RUN);Path directory=Path.of(env("ALERT_NATIVE_IT_EVIDENCE"));
		var mapper=new ObjectMapper();var claim=mapper.readTree(Files.readAllBytes(directory.resolve("one-shot.json")));var pre=mapper.readTree(Files.readAllBytes(directory.resolve("preflight.json")));
		if(!RUN.equals(claim.get("run_id").asString()) || !claim.get("fence_path").asString().equals(FENCE.toString()) || !pre.get("pass").asBoolean()
				|| !"AlertNativeAccountingIT#threeDistinctReceiptsAndOneIncidentFromFourDeliveries".equals(claim.get("selector").asString()))throw new IllegalStateException("One-shot binding required");
		for(String key:List.of("URL","USERNAME","PASSWORD")) {p.add("spring.datasource."+key.toLowerCase(Locale.ROOT),() -> env("PROCESSING_POSTGRES_TEST_"+key));p.add("vanioak.clickhouse."+key.toLowerCase(Locale.ROOT),() -> env("PROCESSING_CLICKHOUSE_TEST_"+key));}
		for(String key:List.of("HOST","PORT","DATABASE"))p.add("spring.data.redis."+key.toLowerCase(Locale.ROOT),() -> env("PROCESSING_REDIS_TEST_"+key));
		for(String key:List.of("HOST","PORT","VHOST","USERNAME","PASSWORD"))p.add("spring.rabbitmq."+(key.equals("VHOST")?"virtual-host":key.toLowerCase(Locale.ROOT)),() -> env("PROCESSING_RABBITMQ_TEST_"+key));
		p.add("spring.jpa.hibernate.ddl-auto",() -> "validate");p.add("vanioak.identity.bootstrap-admin.enabled",() -> "true");p.add("vanioak.identity.bootstrap-admin.username",() -> ADMIN);p.add("vanioak.identity.bootstrap-admin.password",() -> PASSWORD);
		p.add("vanioak.alert.consumer-enabled",() -> "true");p.add("vanioak.alert.fence-path",FENCE::toString);
	}
	@LocalServerPort int port;
	@Autowired ObjectMapper json;
	@Autowired EntityManager em;
	@Autowired StringRedisTemplate redis;
	@Autowired ConnectionFactory connection;
	@Autowired AlertConsumerLifecycle lifecycle;
	final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
	final Map<String,Object> state=new LinkedHashMap<>();
	final List<Map<String,Object>> attempts=new ArrayList<>();
	String access,refresh,sourceChannelName;UUID environment;
	Path evidence() {return Path.of(env("ALERT_NATIVE_IT_EVIDENCE"));}
	void save(String name,Object value) throws Exception {Files.write(evidence().resolve(name),json.writeValueAsBytes(value));}
	void phase(String phase) throws Exception {state.put("run_id",RUN);state.put("port",port);state.put("phase",phase);state.put("attempts",attempts);save("scenario-state.json",state);}
	JsonNode api(String method,String path,Object body,int expected) throws Exception {
		var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).timeout(Duration.ofSeconds(5));if(access!=null)b.header("Authorization","Bearer "+access);
		if(body!=null)b.header("Content-Type","application/json");var response=http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
		assertEquals(expected,response.statusCode(),"API status "+method+" "+path);return json.readTree(response.body()).get("data");
	}
	JsonNode broker(String path) throws Exception {
		String auth=Base64.getEncoder().encodeToString((env("PROCESSING_RABBITMQ_TEST_USERNAME")+":"+env("PROCESSING_RABBITMQ_TEST_PASSWORD")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		var future=http.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:25673/api/"+path)).timeout(Duration.ofSeconds(3)).header("Authorization","Basic "+auth).GET().build(),HttpResponse.BodyHandlers.ofString());
		try {var response=future.get(3,TimeUnit.SECONDS);assertEquals(200,response.statusCode());return json.readTree(response.body());}finally {if(!future.isDone())future.cancel(true);}
	}
	ArrayNode rows(String sql) {
		var result=json.createArrayNode();for(Object value:em.createNativeQuery(sql).setParameter("env",environment).getResultList())result.add(json.readTree(value.toString()));return result;
	}
	Map<String,JsonNode> database() {
		return Map.of("receipts",rows("select row_to_json(t)::text from (select * from alert_event_receipts where environment_id=:env order by event_id)t"),
				"incidents",rows("select row_to_json(t)::text from (select * from incidents where environment_id=:env order by id)t"),
				"history",rows("select row_to_json(t)::text from (select h.* from incident_events h join incidents i on i.id=h.incident_id where i.environment_id=:env order by h.event_at,h.id)t"));
	}
	void awaitRegistration() throws Exception {
		long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);var consumer=(AlertLogConsumer)ReflectionTestUtils.getField(lifecycle,"consumer");
		while(System.nanoTime()<deadline) {
			if(lifecycle.ready()) {
				var list=broker("consumers/processing-test");assertTrue(list.size()<=1);
				if(list.size()==1) {
					var c=list.get(0);assertEquals(consumer.consumerTag(),c.get("consumer_tag").asString());assertEquals("alert.queue",c.get("queue").get("name").asString());
					assertTrue(c.get("ack_required").asBoolean());assertTrue(c.get("active").asBoolean());assertEquals(1,c.get("prefetch_count").asInt());
					Channel nativeSource=(Channel)ReflectionTestUtils.getField(consumer,"nativeSource");assertEquals(nativeSource.getChannelNumber(),c.get("channel_details").get("number").asInt());
					sourceChannelName=c.get("channel_details").get("name").asString();save("registration.json",list);return;
				}
			}
			Thread.sleep(100);
		}throw new AssertionError("Exact Alert registration did not converge");
	}
	JsonNode awaitAck(int count) throws Exception {
		long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
		while(System.nanoTime()<deadline) {
			assertTrue(lifecycle.ready(),"Alert lost readiness");for(JsonNode c:broker("channels"))if(sourceChannelName.equals(c.get("name").asString())) {
				long ack=c.path("message_stats").path("ack").asLong(-1);if(ack>=count){assertEquals(count,ack);return c;}
			}
			Thread.sleep(150);
		}throw new AssertionError("Native source channel ACK count did not converge");
	}
	@Test @Timeout(90) void threeDistinctReceiptsAndOneIncidentFromFourDeliveries() throws Exception {
		phase("startup");awaitRegistration();phase("consumer_ready");
		var login=api("POST","/auth/login",Map.of("username",ADMIN,"password",PASSWORD),200);access=login.get("access_token").asString();refresh=login.get("refresh_token").asString();state.put("user_id",login.get("user").get("id").asString());
		var app=api("POST","/applications",Map.of("name","alert-"+RUN),201);state.put("application_id",app.get("id").asString());
		for(JsonNode e:app.get("environments"))if("DEV".equals(e.get("name").asString()))environment=UUID.fromString(e.get("id").asString());assertNotNull(environment);state.put("environment_id",environment.toString());
		var rule=api("POST","/alert-rules",Map.of("environment_id",environment,"level","ERROR","threshold",2,"window_seconds",300,"cooldown_seconds",60,"resolve_after_seconds",3600,"enabled",true),201);state.put("rule_id",rule.get("id").asString());
		save("queue-before.json",broker("queues/processing-test/alert.queue"));var publisher=new RabbitTemplate(connection);publisher.setMandatory(true);publisher.setReturnsCallback(returned -> { });
		Instant base=Instant.now().truncatedTo(ChronoUnit.MICROS);List<UUID> ids=List.of(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());var bodies=new ArrayList<byte[]>();
		for(int i=0;i<3;i++) {
			var root=json.createObjectNode().put("event_id",ids.get(i).toString()).put("event_type","critical.log").put("occurred_at",base.toString()).put("producer","processing").put("schema_version",1);
			root.putObject("payload").put("event_id",ids.get(i).toString()).put("application_id",app.get("id").asString()).put("environment_id",environment.toString()).put("level","ERROR").put("message","Alert native "+RUN)
					.put("timestamp",base.toString()).put("trace_id",RUN).put("received_at",base.plusSeconds(i).toString());bodies.add(json.writeValueAsBytes(root));save("input-"+i+".json",root);
		}
		for(int delivery=0;delivery<4;delivery++) {
			int index=Math.min(delivery,2);var props=new MessageProperties();props.setContentType("application/json");props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);props.setMessageId(ids.get(index).toString());
			var correlation=new CorrelationData(UUID.randomUUID().toString());var attempt=new LinkedHashMap<String,Object>();attempt.put("event_id",ids.get(index));attempt.put("correlation",correlation.getId());attempt.put("confirmed",false);attempts.add(attempt);phase("publishing");
			publisher.send("critical.exchange","critical.log",new Message(bodies.get(index),props),correlation);var confirm=correlation.getFuture().get(5,TimeUnit.SECONDS);
			attempt.put("confirmed",confirm.ack());attempt.put("returned",correlation.getReturned()!=null);phase("publishing");assertTrue(confirm.ack());assertNull(correlation.getReturned());
			var channel=awaitAck(delivery+1);save("source-channel-after-"+delivery+".json",channel);var db=database();save("postgres-after-"+delivery+".json",db);
			assertEquals(Math.min(delivery+1,3),db.get("receipts").size());
			if(delivery==0) {assertEquals(0,db.get("incidents").size());assertEquals(0,db.get("history").size());}
			else {assertEquals(1,db.get("incidents").size());assertEquals("OPEN",db.get("incidents").get(0).get("status").asString());assertEquals(delivery==1?2:3,db.get("incidents").get(0).get("error_count").asInt());assertEquals(delivery==1?1:2,db.get("history").size());}
		}
		var finalDb=database();var receiptIds=new java.util.HashSet<String>();for(JsonNode r:finalDb.get("receipts"))receiptIds.add(r.get("event_id").asString());assertEquals(Set.copyOf(ids.stream().map(UUID::toString).toList()),receiptIds);
		var incident=finalDb.get("incidents").get(0);state.put("incident_id",incident.get("id").asString());state.put("receipt_ids",receiptIds);state.put("history",finalDb.get("history"));
		for(JsonNode h:finalDb.get("history"))assertEquals("OPENED".equals(h.get("event_type").asString())?2:3,h.get("error_count").asInt());
		String key="vanioak:alert:frequency:"+environment+":"+incident.get("fingerprint").asString()+":"+rule.get("id").asString();var members=redis.opsForZSet().rangeWithScores(key,0,-1);assertNotNull(members);assertEquals(3,members.size());
		var redisRows=new ArrayList<Map<String,Object>>();for(var member:members){int i=ids.indexOf(UUID.fromString(member.getValue()));assertTrue(i>=0);assertEquals((double)(base.plusSeconds(i).getEpochSecond()*1_000_000L+base.getNano()/1000),member.getScore());redisRows.add(Map.of("event_id",member.getValue(),"score",member.getScore()));}assertTrue(redis.getExpire(key)>0);save("redis-frequency.json",Map.of("key",key,"members",redisRows,"ttl",redis.getExpire(key)));
		var detail=api("GET","/incidents/"+incident.get("id").asString(),null,200);assertEquals(3,detail.get("error_count").asInt());assertEquals(2,detail.get("events").size());save("incident-api.json",detail);
		var list=api("GET","/incidents?application_id="+app.get("id").asString()+"&environment_id="+environment,null,200);assertEquals(1,list.get("items").size());
		var queues=broker("queues/processing-test");save("queues-active-final.json",queues);assertEquals(6,queues.size());for(JsonNode q:queues){assertEquals(0,q.path("messages_ready").asInt(-1));assertEquals(0,q.path("messages_unacknowledged").asInt(-1));if(!"alert.queue".equals(q.get("name").asString()))assertEquals(0,q.path("consumers").asInt(-1));}
		state.put("outcome","PASS");phase("verified");
	}
	@AfterEach @Timeout(20) void cleanup() throws Exception {
		try {lifecycle.close();state.put("shutdown_settled",lifecycle.shutdownSettled());assertTrue(lifecycle.shutdownSettled());if(refresh!=null){api("POST","/auth/logout",Map.of("refresh_token",refresh),200);state.put("sessions_logged_out",1);}phase("cleanup_complete");}
		finally {http.shutdownNow();}
	}
}
