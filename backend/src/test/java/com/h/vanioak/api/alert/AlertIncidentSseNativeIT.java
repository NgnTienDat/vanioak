package com.h.vanioak.api.alert;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicReference;
import java.net.URI;
import java.io.InputStream;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.junit.jupiter.api.BeforeEach;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.h.vanioak.modules.realtime.internal.consumer.RealtimeLogConsumer;
import com.h.vanioak.modules.realtime.internal.subscription.RealtimeService;
import com.h.vanioak.modules.realtime.api.RealtimeFacade;
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
		"spring.flyway.enabled=false","vanioak.processing.consumer-enabled=false","vanioak.realtime.enabled=true","vanioak.alert.resolver-enabled=false",
		"logging.level.root=INFO","logging.level.org.springframework=INFO","logging.level.org.hibernate=INFO","logging.level.com.h.vanioak=INFO"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class AlertIncidentSseNativeIT {
	static final Path FENCE=Path.of("D:/Web System Development/vanioak").resolve(".local/alert-recovery.fence");
	static final Path RT_FENCE=Path.of("D:/Web System Development/vanioak/.local/realtime-native-happy-20261026/realtime.fence");
	static final String RUN=System.getenv("INCIDENT_SSE_NATIVE_RUN"),ADMIN="alert-admin-"+RUN,PASSWORD=UUID.randomUUID().toString();
	static String env(String key) {return Objects.requireNonNull(System.getenv(key));}
	@DynamicPropertySource static void isolated(DynamicPropertyRegistry p) throws Exception {
		if(!"true".equals(System.getenv("INCIDENT_SSE_NATIVE_APPROVED")) || !FENCE.equals(Path.of(env("INCIDENT_SSE_NATIVE_FENCE")))
				|| Files.exists(RT_FENCE) || Files.exists(FENCE) || !Files.isDirectory(FENCE.getParent()) || !"jdbc:postgresql://127.0.0.1:25432/processing_test".equals(env("PROCESSING_POSTGRES_TEST_URL"))
				|| !"127.0.0.1".equals(env("PROCESSING_RABBITMQ_TEST_HOST")) || !"25672".equals(env("PROCESSING_RABBITMQ_TEST_PORT"))
				|| !"processing-test".equals(env("PROCESSING_RABBITMQ_TEST_VHOST")) || !"26379".equals(env("PROCESSING_REDIS_TEST_PORT")))
			throw new IllegalStateException("Approved isolated Alert fixture/fence required");
		UUID.fromString(RUN);Path directory=Path.of(env("INCIDENT_SSE_NATIVE_EVIDENCE"));
		var mapper=new ObjectMapper();var claim=mapper.readTree(Files.readAllBytes(directory.resolve("one-shot.json")));var pre=mapper.readTree(Files.readAllBytes(directory.resolve("preflight.json")));
		if(!RUN.equals(claim.get("run_id").asString()) || !claim.get("fence_path").asString().equals(FENCE.toString()) || !pre.get("pass").asBoolean()
				|| !"AlertIncidentSseNativeIT#committedIncidentSseIsScopedAndUnavailableRealtimeDoesNotBlockAck".equals(claim.get("selector").asString()))throw new IllegalStateException("One-shot binding required");
		for(String key:List.of("URL","USERNAME","PASSWORD")) {p.add("spring.datasource."+key.toLowerCase(Locale.ROOT),() -> env("PROCESSING_POSTGRES_TEST_"+key));p.add("vanioak.clickhouse."+key.toLowerCase(Locale.ROOT),() -> env("PROCESSING_CLICKHOUSE_TEST_"+key));}
		for(String key:List.of("HOST","PORT","DATABASE"))p.add("spring.data.redis."+key.toLowerCase(Locale.ROOT),() -> env("PROCESSING_REDIS_TEST_"+key));
		for(String key:List.of("HOST","PORT","VHOST","USERNAME","PASSWORD"))p.add("spring.rabbitmq."+(key.equals("VHOST")?"virtual-host":key.toLowerCase(Locale.ROOT)),() -> env("PROCESSING_RABBITMQ_TEST_"+key));
		p.add("spring.jpa.hibernate.ddl-auto",() -> "validate");p.add("vanioak.identity.bootstrap-admin.enabled",() -> "true");p.add("vanioak.identity.bootstrap-admin.username",() -> ADMIN);p.add("vanioak.identity.bootstrap-admin.password",() -> PASSWORD);
		p.add("vanioak.alert.consumer-enabled",() -> "true");p.add("vanioak.alert.fence-path",FENCE::toString);p.add("vanioak.realtime.fence-path",RT_FENCE::toString);
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
	Path evidence() {return Path.of(env("INCIDENT_SSE_NATIVE_EVIDENCE"));}
	void save(String name,Object value) throws Exception {Files.write(evidence().resolve(name),json.writeValueAsBytes(value));}
	void phase(String phase) throws Exception {state.put("run_id",RUN);state.put("port",port);state.put("phase",phase);state.put("attempts",attempts);save("scenario-state.json",state);}
	JsonNode api(String method,String path,Object body,int expected) throws Exception {return api(method,path,body,expected,access);}
	JsonNode api(String method,String path,Object body,int expected,String token) throws Exception {
		var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).timeout(Duration.ofSeconds(5));if(token!=null)b.header("Authorization","Bearer "+token);
		if(body!=null)b.header("Content-Type","application/json");var response=http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
		assertEquals(expected,response.statusCode(),"API status "+method+" "+path);return json.readTree(response.body()).get("data");
	}
	JsonNode broker(String path) throws Exception {
		String auth=Base64.getEncoder().encodeToString((env("PROCESSING_RABBITMQ_TEST_USERNAME")+":"+env("PROCESSING_RABBITMQ_TEST_PASSWORD")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		var future=http.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:25673/api/"+path)).timeout(Duration.ofSeconds(3)).header("Authorization","Basic "+auth).GET().build(),HttpResponse.BodyHandlers.ofString());
		try {var response=future.get(3,TimeUnit.SECONDS);assertEquals(200,response.statusCode());return json.readTree(response.body());}finally {if(!future.isDone())future.cancel(true);}
	}
	JsonNode broker(String path,long deadline) throws Exception {
		long budget=Math.min(TimeUnit.SECONDS.toNanos(1),deadline-System.nanoTime());assertTrue(budget>0);
		String auth=Base64.getEncoder().encodeToString((env("PROCESSING_RABBITMQ_TEST_USERNAME")+":"+env("PROCESSING_RABBITMQ_TEST_PASSWORD")).getBytes(StandardCharsets.UTF_8));
		var future=http.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:25673/api/"+path)).timeout(Duration.ofNanos(budget)).header("Authorization","Basic "+auth).GET().build(),HttpResponse.BodyHandlers.ofString());
		try{var response=future.get(budget,TimeUnit.NANOSECONDS);assertEquals(200,response.statusCode());return json.readTree(response.body());}finally{if(!future.isDone())future.cancel(true);}
	}
	ArrayNode rows(String sql) {
		var result=json.createArrayNode();for(Object value:em.createNativeQuery(sql).setParameter("env",environment).getResultList())result.add(json.readTree(value.toString()));return result;
	}
	Map<String,JsonNode> database() {
		return Map.of("receipts",rows("select row_to_json(t)::text from (select * from alert_event_receipts where environment_id=:env order by event_id)t"),
				"incidents",rows("select row_to_json(t)::text from (select * from incidents where environment_id=:env order by id)t"),
				"history",rows("select row_to_json(t)::text from (select h.* from incident_events h join incidents i on i.id=h.incident_id where i.environment_id=:env order by h.event_at,h.id)t"));
	}
	@Autowired RealtimeLogConsumer rtConsumer;
	@MockitoSpyBean RealtimeService realtime;
	final List<Observation> observations=new CopyOnWriteArrayList<>();
	final List<Map<String,String>> sessions=new ArrayList<>();
	final List<Probe> streams=new ArrayList<>();
	boolean realtimeStopVerified;
	record Observation(RealtimeFacade.IncidentEvent event,RealtimeFacade.Fanout fanout) { }
	@BeforeEach void observeRealFacade() {
		doAnswer(call -> {var event=(RealtimeFacade.IncidentEvent)call.getArgument(0);var result=(RealtimeFacade.Fanout)call.callRealMethod();observations.add(new Observation(event,result));return result;})
				.when(realtime).publishIncident(any());
	}
	String login(String username,String password) throws Exception {
		var data=api("POST","/auth/login",Map.of("username",username,"password",password),200,null);
		sessions.add(Map.of("access_token",data.get("access_token").asString(),"refresh_token",data.get("refresh_token").asString(),"user_id",data.get("user").get("id").asString()));
		state.put("user_ids",sessions.stream().map(s -> s.get("user_id")).toList());return data.get("access_token").asString();
	}
	void awaitRegistrations() throws Exception {
		long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
		var alert=(AlertLogConsumer)ReflectionTestUtils.getField(lifecycle,"consumer");
		while(System.nanoTime()<end) {
			if(lifecycle.ready() && realtime.ready()) {
				var list=broker("consumers/processing-test",end);assertTrue(list.size()<=2);
				if(list.size()==2) {
					var connections=broker("connections",end);var queues=new java.util.HashSet<String>();boolean joinedAll=true;
					for(JsonNode c:list) {
						String q=c.get("queue").get("name").asString();assertTrue(queues.add(q));assertEquals("processing-test",c.get("queue").get("vhost").asString());
						Channel nativeChannel;String tag;
						if(q.equals("alert.queue")){nativeChannel=(Channel)ReflectionTestUtils.getField(alert,"nativeSource");tag=alert.consumerTag();sourceChannelName=c.get("channel_details").get("name").asString();}
						else {assertEquals("realtime.queue",q);nativeChannel=(Channel)ReflectionTestUtils.getField(rtConsumer,"sourceChannel");tag=(String)ReflectionTestUtils.getField(rtConsumer,"tag");assertTrue(c.get("exclusive").asBoolean());}
						assertNotNull(nativeChannel);assertTrue(nativeChannel.isOpen());assertEquals(tag,c.get("consumer_tag").asString());assertEquals(nativeChannel.getChannelNumber(),c.get("channel_details").get("number").asInt());
						assertTrue(c.get("ack_required").asBoolean());assertTrue(c.get("active").asBoolean());assertEquals(1,c.get("prefetch_count").asInt());
						JsonNode joined=null;for(JsonNode row:connections)if(row.get("name").asString().equals(c.get("channel_details").get("connection_name").asString()))joined=row;
						if(joined==null){joinedAll=false;break;}
						assertEquals(nativeChannel.getConnection().getClientProvidedName(),joined.get("client_properties").get("connection_name").asString());
					}
					if(joinedAll && queues.size()==2 && System.nanoTime()<end){assertTrue(lifecycle.ready()&&realtime.ready());save("registration.json",list);return;}
				}
			}
			Thread.sleep(100);
		}throw new AssertionError("Exact registrations did not converge");
	}
	JsonNode awaitAck(int count) throws Exception {
		long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
		while(System.nanoTime()<end){assertTrue(lifecycle.ready());for(JsonNode c:broker("channels",end))if(sourceChannelName.equals(c.get("name").asString()) && c.path("message_stats").path("ack").asLong(-1)>=count){assertEquals(count,c.get("message_stats").get("ack").asInt());assertTrue(System.nanoTime()<end);return c;}Thread.sleep(100);}
		throw new AssertionError("Source ACK deadline");
	}
	@Test @Timeout(120) void committedIncidentSseIsScopedAndUnavailableRealtimeDoesNotBlockAck() throws Exception {
		phase("startup");awaitRegistrations();phase("consumer_ready");access=login(ADMIN,PASSWORD);
		var app=api("POST","/applications",Map.of("name","incident-sse-"+RUN),201);state.put("application_id",app.get("id").asString());
		for(JsonNode e:app.get("environments"))if("DEV".equals(e.get("name").asString()))environment=UUID.fromString(e.get("id").asString());assertNotNull(environment);state.put("environment_id",environment.toString());
		var tokens=new ArrayList<String>();for(int i=0;i<2;i++){String name="incident-sse-engineer-"+RUN+"-"+i,password=UUID.randomUUID().toString();var user=api("POST","/users",Map.of("username",name,"password",password),201);if(i==0)api("POST","/applications/"+app.get("id").asString()+"/engineers/"+user.get("id").asString(),null,200);tokens.add(login(name,password));}
		var rule=api("POST","/alert-rules",Map.of("environment_id",environment,"level","ERROR","threshold",2,"window_seconds",300,"cooldown_seconds",60,"resolve_after_seconds",3600,"enabled",true),201);state.put("rule_id",rule.get("id").asString());
		String target="/realtime/stream?application_id="+app.get("id").asString()+"&environment_id="+environment+"&level=ERROR";
		api("GET",target,null,403,tokens.get(1));state.put("unassigned_explicit_status",403);
		var pending=new ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<InputStream>>>();
		for(int i=0;i<2;i++)pending.add(http.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+(i==0?target:"/realtime/stream"))).timeout(Duration.ofSeconds(25)).header("Authorization","Bearer "+tokens.get(i)).header("Accept","text/event-stream").GET().build(),HttpResponse.BodyHandlers.ofInputStream()));
		for(int i=0;i<2;i++){var response=pending.get(i).get(27,TimeUnit.SECONDS);assertEquals(200,response.statusCode());assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));var probe=new Probe(response.body(),Path.of(env("INCIDENT_SSE_NATIVE_EVIDENCE")).resolve("sse-"+i+".txt"),json);streams.add(probe);probe.start();}
		assertEquals(2,((List<?>)ReflectionTestUtils.getField(realtime,"owned")).size());phase("streams_open");
		var publisher=new RabbitTemplate(connection);publisher.setMandatory(true);publisher.setReturnsCallback(r -> { });String message="Incident SSE "+RUN+" café Ω";
		for(int index=0;index<4;index++) {
			if(index==3){awaitFrames();Thread.sleep(3000);checkFrames(message,app.get("id").asString());phase("realtime_stopping");streams.forEach(Probe::beginPlannedStop);rtConsumer.stop();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean retired=false;
				while(System.nanoTime()<end){boolean present=false;for(JsonNode c:broker("consumers/processing-test",end))if("realtime.queue".equals(c.get("queue").get("name").asString()))present=true;
					if(!present && !realtime.ready() && streams.stream().noneMatch(p -> p.reader.isAlive())){retired=true;break;}Thread.sleep(50);}
				assertTrue(retired,"Realtime registration/stream retirement");assertTrue(lifecycle.ready());realtimeStopVerified=true;for(var p:streams)p.capturedFrames(true);phase("realtime_unavailable");}
			UUID id=UUID.randomUUID();Instant received=Instant.now().truncatedTo(ChronoUnit.MICROS);var body=json.createObjectNode().put("event_id",id.toString()).put("event_type","critical.log").put("occurred_at",received.toString()).put("producer","processing").put("schema_version",1);
			body.putObject("payload").put("event_id",id.toString()).put("application_id",app.get("id").asString()).put("environment_id",environment.toString()).put("level","ERROR").put("message",message).put("timestamp",received.toString()).put("trace_id",RUN).put("received_at",received.toString());
			byte[] bytes=json.writeValueAsBytes(body);save("input-"+index+".json",body);var props=new MessageProperties();props.setMessageId(id.toString());props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);props.setContentType("application/json");var correlation=new CorrelationData(UUID.randomUUID().toString());var attempt=new LinkedHashMap<String,Object>();attempt.put("event_id",id);attempt.put("correlation",correlation.getId());attempt.put("confirmed",false);attempts.add(attempt);phase("publishing");
			publisher.send("critical.exchange","critical.log",new Message(bytes,props),correlation);var confirm=correlation.getFuture().get(5,TimeUnit.SECONDS);attempt.put("confirmed",confirm.ack());attempt.put("returned",correlation.getReturned()!=null);phase("publishing");assertTrue(confirm.ack());assertNull(correlation.getReturned());
			save("source-channel-after-"+index+".json",awaitAck(index+1));var db=database();save("postgres-after-"+index+".json",db);assertEquals(index+1,db.get("receipts").size());
			if(index==0){assertTrue(db.get("incidents").isEmpty());assertTrue(db.get("history").isEmpty());}else{assertEquals(1,db.get("incidents").size());assertEquals(index+1,db.get("incidents").get(0).get("error_count").asInt());assertEquals("OPEN",db.get("incidents").get(0).get("status").asString());assertEquals(index,db.get("history").size());}
		}
		long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(observations.size()<3 && System.nanoTime()<end)Thread.sleep(50);assertEquals(3,observations.size());assertFalse(observations.get(2).fanout().accounted());
		var db=database();var incident=db.get("incidents").get(0);state.put("incident_id",incident.get("id").asString());
		var serialized=new ArrayList<Map<String,Object>>();for(var o:observations){var e=o.event();assertEquals("alert",e.producer());assertEquals(1,e.schemaVersion());assertEquals("OPEN",e.status());JsonNode history=null;for(JsonNode h:db.get("history"))if(e.eventId().toString().equals(h.get("id").asString()))history=h;assertNotNull(history);assertEquals(e.occurredAt(),Instant.parse(history.get("event_at").asString()));assertEquals(environment,e.environmentId());assertEquals(UUID.fromString(app.get("id").asString()),e.applicationId());assertEquals(incident.get("id").asString(),e.incidentId().toString());serialized.add(Map.of("event",e,"fanout",o.fanout()));}save("facade-observations.json",serialized);
		String key="vanioak:alert:frequency:"+environment+":"+incident.get("fingerprint").asString()+":"+rule.get("id").asString();var members=redis.opsForZSet().rangeWithScores(key,0,-1);assertNotNull(members);assertEquals(4,members.size());for(var member:members){JsonNode receipt=null;for(JsonNode r:db.get("receipts"))if(member.getValue().equals(r.get("event_id").asString()))receipt=r;assertNotNull(receipt);Instant t=Instant.parse(receipt.get("received_at").asString());assertEquals((double)(t.getEpochSecond()*1_000_000L+t.getNano()/1000),member.getScore().doubleValue());}assertTrue(redis.getExpire(key)>0);save("redis-frequency.json",Map.of("key",key,"members",members,"ttl",redis.getExpire(key)));
		checkFrames(message,app.get("id").asString());state.put("outcome","PASS");state.put("frames",List.of(streams.get(0).frames.size(),streams.get(1).frames.size()));phase("verified");
	}
	void awaitFrames() throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(streams.get(0).frames.size()<2 && System.nanoTime()<end){for(var p:streams)assertNull(p.failure.get());Thread.sleep(50);}assertEquals(2,streams.get(0).frames.size());}
	void checkFrames(String message,String app) throws Exception {
		assertFrames(streams.get(0).capturedFrames(realtimeStopVerified),streams.get(1).capturedFrames(realtimeStopVerified),message,app,environment,observations.get(1).event().lastSeenAt());
	}
	static void assertFrames(List<Frame> authorized,List<Frame> unauthorized,String message,String app,UUID environment,Instant lastSeen) {
		assertEquals(2,authorized.size());assertTrue(unauthorized.isEmpty());var a=authorized.get(0);var b=authorized.get(1);assertEquals("incident.created",a.name());assertEquals("incident.updated",b.name());assertEquals("ERROR",a.data().get("level").asString());assertEquals(7,a.data().size());assertEquals(3,b.data().size());assertEquals(app,a.data().get("application_id").asString());assertEquals(environment.toString(),a.data().get("environment_id").asString());assertEquals(message,a.data().get("message").asString());assertEquals("OPEN",a.data().get("status").asString());assertEquals(2,a.data().get("error_count").asInt());assertEquals(3,b.data().get("error_count").asInt());assertEquals(a.data().get("incident_id"),b.data().get("incident_id"));assertEquals(lastSeen,Instant.parse(b.data().get("last_seen_at").asString()));
	}
	@AfterEach @Timeout(20) void cleanup() throws Exception {
		Exception failure=null;try {rtConsumer.close();lifecycle.close();state.put("shutdown_settled",lifecycle.shutdownSettled());for(var p:streams)try{p.close();}catch(Exception e){failure=e;}int logged=0;for(var s:sessions)try{api("POST","/auth/logout",Map.of("refresh_token",s.get("refresh_token")),200,s.get("access_token"));logged++;}catch(Exception|AssertionError e){failure=new IllegalStateException("Owned session cleanup failed");}state.put("sessions_logged_out",logged);phase(failure==null?"cleanup_complete":"cleanup_partial");assertTrue(lifecycle.shutdownSettled());if(failure!=null)throw failure;}finally{http.shutdownNow();}
	}
	record Frame(String name, JsonNode data) { }
	static Frame parse(String block, ObjectMapper mapper) {
		String name = null, data = null;
		for (String line : block.replace("\r\n", "\n").split("\n")) {
			if (line.isEmpty() || line.startsWith(":")) continue;
			if (line.startsWith("event:") && name == null) name = line.substring(6).stripLeading();
			else if (line.startsWith("data:") && data == null) data = line.substring(5).stripLeading();
			else throw new IllegalArgumentException("Unexpected SSE field");
		}
		if (name == null && data == null) return null;
		if (!java.util.Set.of("incident.created","incident.updated").contains(name) || data == null) throw new IllegalArgumentException("Unexpected SSE frame");
		return new Frame(name, mapper.readTree(data));
	}
	static final class Probe implements AutoCloseable {
		final List<Frame> frames = new CopyOnWriteArrayList<>(); final AtomicReference<Throwable> failure = new AtomicReference<>();
		final InputStream body; final FileChannel journal; final ObjectMapper mapper; final Thread reader; volatile boolean closed;
		final Path path;
		final java.util.function.BiFunction<String,ObjectMapper,Frame> parser;
		private boolean plannedStop,terminalObserved,failureFromBody,failureAfterStop;
		private long stopNanos;
		private int bufferedBytes;
		private List<Frame> beforeStopFrames;
		private byte[] beforeStopJournal;
		Probe(InputStream body, Path path, ObjectMapper mapper) throws Exception {
			this(body,path,mapper,AlertIncidentSseNativeIT::parse);
		}
		Probe(InputStream body, Path path, ObjectMapper mapper, java.util.function.BiFunction<String,ObjectMapper,Frame> parser) throws Exception {
			this.parser=parser;
			this.body = body; this.mapper = mapper;this.path=path;
			journal = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
			reader = new Thread(this::read, "realtime-it-sse-reader"); reader.setDaemon(true);
		}
		void start() { reader.start(); }
		synchronized void beginPlannedStop() {
			assertFalse(plannedStop,"Stop already initiated");assertNull(failure.get(),"Reader failed before planned stop");assertTrue(reader.isAlive(),"Reader ended before planned stop");
			try {beforeStopFrames=journalFrames();beforeStopJournal=Files.readAllBytes(path);}catch(Exception error){throw new AssertionError("Cannot freeze complete SSE journal",error);}
			stopNanos=System.nanoTime();plannedStop=true;
		}
		private synchronized void failed(Exception error,boolean bodyRead,int pending,long observedAt) {
			if(!closed && failure.compareAndSet(null,error)) {
				failureFromBody=bodyRead;failureAfterStop=plannedStop && observedAt>=stopNanos;
				bufferedBytes=pending;terminalObserved|=bodyRead;
			}
		}
		private static boolean closureChain(Throwable error) {
			var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());
			while(error!=null) {
				if(!seen.add(error) || seen.size()>8)return false;
				if(error.getClass()==java.io.IOException.class && "closed".equals(error.getMessage())) {
					error=error.getCause();continue;
				}
				// JDK HttpClient can reach EOF while awaiting the next chunk after an intentional stop.
				var eof=error.getCause();
				return seen.size()>1 && seen.size()<8 && error.getClass()==java.io.IOException.class
						&& "chunked transfer encoding, state: READING_LENGTH".equals(error.getMessage())
						&& eof!=null && eof.getClass()==java.io.EOFException.class
						&& "EOF reached while reading".equals(eof.getMessage()) && eof.getCause()==null;
			}
			return !seen.isEmpty();
		}
		private List<Frame> journalFrames() throws Exception {
			byte[] raw=Files.readAllBytes(path);assertTrue(raw.length<=65536);
			String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString().replace("\r\n","\n");
			assertTrue(text.isEmpty() || text.endsWith("\n\n"),"Incomplete SSE journal");
			var captured=new ArrayList<Frame>();for(String block:text.split("\n\n")){var frame=parser.apply(block,mapper);if(frame!=null)captured.add(frame);}
			assertEquals(List.copyOf(frames),captured,"Journal differs from parsed frames");return List.copyOf(captured);
		}
		synchronized List<Frame> capturedFrames(boolean normalStopVerified) throws Exception {
			if(!normalStopVerified) {
				assertNull(failure.get(),"Reader failed during functional verification");assertTrue(reader.isAlive(),"Reader ended during functional verification");return journalFrames();
			}
			var error=failure.get();var causes=new ArrayList<String>();var causeClosed=new ArrayList<Boolean>();var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());
			for(Throwable cause=error;cause!=null && seen.add(cause) && causes.size()<8;cause=cause.getCause()){causes.add(cause.getClass().getName());causeClosed.add(cause.getClass()==java.io.IOException.class && "closed".equals(cause.getMessage()));}
			Files.write(path.resolveSibling(path.getFileName()+".terminal.json"),mapper.writeValueAsBytes(Map.of("planned_stop",plannedStop,"reader_alive",reader.isAlive(),"terminal_observed",terminalObserved,"failure_from_body",failureFromBody,"failure_after_stop",failureAfterStop,"buffered_bytes",bufferedBytes,"cause_types",causes,"cause_closed_flags",causeClosed,"normal_shutdown_verified",normalStopVerified,"closure_chain",closureChain(error))));
			assertTrue(plannedStop);assertFalse(reader.isAlive(),"Reader retirement not verified");assertTrue(terminalObserved,"Stream termination not observed");assertEquals(0,bufferedBytes,"Incomplete terminal SSE data");
			if(error!=null)assertTrue(failureAfterStop && failureFromBody && closureChain(error),"Unexpected reader failure; see terminal diagnostics");
			byte[] raw=Files.readAllBytes(path);assertTrue(raw.length>=beforeStopJournal.length && java.util.Arrays.equals(beforeStopJournal,java.util.Arrays.copyOf(raw,beforeStopJournal.length)),"Pre-stop journal changed");
			var captured=journalFrames();assertEquals(beforeStopFrames,captured,"Data frames changed after planned stop");return captured;
		}
		void read() {
			var block = new ByteArrayOutputStream();
			try {
				int previous = -1, suffix = 0, total = 0, b;
				while (!closed) {
					try {b=body.read();}catch(java.io.IOException ex){failed(ex,true,block.size(),System.nanoTime());return;}
					long observedAt=System.nanoTime();
					if(b<0){synchronized(this){terminalObserved=true;bufferedBytes=block.size();if(!plannedStop || observedAt<stopNanos)failed(new java.io.EOFException("Unplanned SSE EOF"),false,block.size(),observedAt);else if(block.size()!=0)failed(new IllegalStateException("Incomplete terminal SSE block"),false,block.size(),observedAt);}return;}
					synchronized(this) {
						if (++total > 65536 || block.size() >= 16384) throw new IllegalStateException("SSE evidence bound exceeded");
						block.write(b);bufferedBytes=block.size();suffix=(suffix<<8)|b;
						if((b==10 && previous==10) || suffix==0x0d0a0d0a) {
							byte[] raw=block.toByteArray();ByteBuffer bytes=ByteBuffer.wrap(raw);while(bytes.hasRemaining())journal.write(bytes);journal.force(true);
							String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();Frame frame=parser.apply(text,mapper);
							if(frame!=null){if(frames.size()>=8)throw new IllegalStateException("Frame bound exceeded");frames.add(frame);}block.reset();bufferedBytes=0;
						}
						previous=b;
					}
				}
			}catch(Exception ex){failed(ex,false,block.size(),System.nanoTime());}
		}
		@Override public void close() throws Exception {
			closed = true;
			try { body.close(); } finally { try { reader.join(1500); if(reader.isAlive())throw new IllegalStateException("Owned SSE reader did not settle"); } finally { journal.close(); } }
		}
	}
}
