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
@TestPropertySource(locations="classpath:application.properties",properties={"server.address=127.0.0.1","spring.rabbitmq.dynamic=false","spring.flyway.enabled=false","vanioak.processing.consumer-enabled=false","vanioak.realtime.enabled=true","vanioak.alert.consumer-enabled=false","vanioak.alert.resolver-enabled=true","vanioak.alert.resolve-interval=1s","logging.level.root=INFO","logging.level.org.springframework=INFO","logging.level.org.hibernate=INFO","logging.level.com.h.vanioak=INFO"})
@org.springframework.test.context.ContextConfiguration(initializers=AlertResolvedSseNativeIT.IsolationGuard.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class AlertResolvedSseNativeIT {
 static final String DB="vanioak_resolved_20261027_01",URL="jdbc:postgresql://127.0.0.1:25432/"+DB+"?currentSchema=public";
 static final Path ALERT=Path.of("D:/Web System Development/vanioak/.local/alert-recovery.fence"),RT=Path.of("D:/Web System Development/vanioak/.local/realtime-native-happy-20261026/realtime.fence");
 static final String RUN=System.getenv("INCIDENT_SSE_NATIVE_RUN"),ADMIN="resolved-admin-"+RUN,PASSWORD=UUID.randomUUID().toString();
 static String env(String key){return Objects.requireNonNull(System.getenv(key),key);}
 static void requireTarget(String url,String user,String database){if(!URL.equals(url)||!DB.equals(user)||!"15".equals(database))throw new IllegalStateException("Private database/role/Redis target required");}
 @DynamicPropertySource static void isolated(DynamicPropertyRegistry p) throws Exception {
  if(!"true".equals(env("RESOLVED_NATIVE_APPROVED"))||Files.exists(RT)||!Files.exists(ALERT))throw new IllegalStateException("Approved marker state required");
  UUID.fromString(RUN);requireTarget(env("PROCESSING_POSTGRES_TEST_URL"),env("PROCESSING_POSTGRES_TEST_USERNAME"),env("PROCESSING_REDIS_TEST_DATABASE"));
  var j=new ObjectMapper();var evidence=Path.of(env("INCIDENT_SSE_NATIVE_EVIDENCE"));var claim=j.readTree(Files.readAllBytes(evidence.resolve("one-shot.json")));var pre=j.readTree(Files.readAllBytes(evidence.resolve("preflight.json")));
  if(!RUN.equals(claim.get("run_id").asString())||!"AlertResolvedSseNativeIT#isolatedCommittedIncidentResolvesOnceOverSse".equals(claim.get("selector").asString())||!pre.get("pass").asBoolean())throw new IllegalStateException("One-shot/preflight binding required");
  for(String key:List.of("URL","USERNAME","PASSWORD")){p.add("spring.datasource."+key.toLowerCase(Locale.ROOT),()->env("PROCESSING_POSTGRES_TEST_"+key));p.add("spring.flyway."+(key.equals("USERNAME")?"user":key.toLowerCase(Locale.ROOT)),()->env("PROCESSING_POSTGRES_TEST_"+key));p.add("vanioak.clickhouse."+key.toLowerCase(Locale.ROOT),()->env("PROCESSING_CLICKHOUSE_TEST_"+key));}
  for(String key:List.of("HOST","PORT","DATABASE"))p.add("spring.data.redis."+key.toLowerCase(Locale.ROOT),()->env("PROCESSING_REDIS_TEST_"+key));
  for(String key:List.of("HOST","PORT","VHOST","USERNAME","PASSWORD"))p.add("spring.rabbitmq."+(key.equals("VHOST")?"virtual-host":key.toLowerCase(Locale.ROOT)),()->env("PROCESSING_RABBITMQ_TEST_"+key));
  p.add("spring.flyway.enabled",()->"false");p.add("spring.jpa.hibernate.ddl-auto",()->"validate");p.add("spring.sql.init.mode",()->"never");p.add("spring.flyway.baseline-on-migrate",()->"false");p.add("spring.flyway.clean-disabled",()->"true");
  p.add("security.jwt.access-secret",()->env("RESOLVED_ACCESS_SECRET"));p.add("security.jwt.refresh-secret",()->env("RESOLVED_REFRESH_SECRET"));
  p.add("vanioak.identity.bootstrap-admin.enabled",()->"true");p.add("vanioak.identity.bootstrap-admin.username",()->ADMIN);p.add("vanioak.identity.bootstrap-admin.password",()->PASSWORD);p.add("vanioak.realtime.fence-path",RT::toString);
 }
 public static class IsolationGuard implements org.springframework.context.ApplicationContextInitializer<org.springframework.context.ConfigurableApplicationContext> {
  final List<Map<String,Object>> diagnostics=new CopyOnWriteArrayList<>();
  void audit(String phase,org.springframework.beans.factory.config.ConfigurableListableBeanFactory factory,boolean includeNonSingletons,boolean eager){
   var types=new LinkedHashMap<String,Object>();for(Class<?> type:List.of(javax.sql.DataSource.class,jakarta.persistence.EntityManagerFactory.class,org.springframework.transaction.PlatformTransactionManager.class,org.springframework.data.redis.connection.RedisConnectionFactory.class)){var names=factory.getBeanNamesForType(type,includeNonSingletons,eager);var entries=new ArrayList<Map<String,String>>();for(String name:names){Class<?> actual=factory.getType(name,false);entries.add(Map.of("name",name,"type",actual==null?"not-yet-resolved":actual.getName()));}types.put(type.getSimpleName(),entries);}diagnostics.add(Map.of("phase",phase,"beans",types));String directory=System.getenv("INCIDENT_SSE_NATIVE_EVIDENCE");if(directory!=null)try{Files.write(Path.of(directory,"persistence-wiring.json"),new ObjectMapper().writeValueAsBytes(diagnostics));}catch(Exception error){throw new IllegalStateException("Cannot record isolation diagnostics",error);}
  }
  static void config(org.springframework.core.env.Environment e){
   requireTarget(e.getProperty("spring.datasource.url"),e.getProperty("spring.datasource.username"),e.getProperty("spring.data.redis.database"));
   if(!URL.equals(e.getProperty("spring.flyway.url"))||!DB.equals(e.getProperty("spring.flyway.user"))||!Boolean.FALSE.equals(e.getProperty("spring.flyway.enabled",Boolean.class))||!"validate".equals(e.getProperty("spring.jpa.hibernate.ddl-auto"))||!"never".equals(e.getProperty("spring.sql.init.mode")))throw new IllegalStateException("Migrated private database configuration required");
  }
  static void dataSource(Object bean){
   if(!(bean instanceof com.zaxxer.hikari.HikariDataSource ds)||!URL.equals(ds.getJdbcUrl())||!DB.equals(ds.getUsername()))throw new IllegalStateException("Unexpected actual datasource target/type");
  }
  void verified(org.springframework.beans.factory.config.ConfigurableListableBeanFactory factory){
   audit("before-business-initialization",factory,true,true);
   var sources=factory.getBeansOfType(javax.sql.DataSource.class,true,true);var factories=factory.getBeansOfType(jakarta.persistence.EntityManagerFactory.class,true,true);var managers=factory.getBeansOfType(org.springframework.transaction.PlatformTransactionManager.class,true,true);var redisFactories=factory.getBeansOfType(org.springframework.data.redis.connection.RedisConnectionFactory.class,true,true);
   if(sources.size()!=1||factories.size()!=1||managers.size()!=1||redisFactories.size()!=1)throw new IllegalStateException("Unexpected actual persistence cardinality; see isolation diagnostics");
   var ds=sources.values().iterator().next();dataSource(ds);var emf=factories.values().iterator().next();var manager=managers.values().iterator().next();
   if(!(emf instanceof org.springframework.orm.jpa.EntityManagerFactoryInfo info)||info.getDataSource()!=ds||!(manager instanceof org.springframework.orm.jpa.JpaTransactionManager tx)||(tx.getEntityManagerFactory()!=emf&&tx.getEntityManagerFactory()!=info.getNativeEntityManagerFactory())||tx.getDataSource()!=ds)throw new IllegalStateException("Unexpected JPA transaction/datasource association");
   var redis=redisFactories.values().iterator().next();if(!(redis instanceof org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory lettuce)||lettuce.getDatabase()!=15||!"127.0.0.1".equals(lettuce.getHostName())||lettuce.getPort()!=26379)throw new IllegalStateException("Unexpected actual Redis target");
   try(var connection=ds.getConnection();var statement=connection.createStatement();var result=statement.executeQuery("select current_database(),current_user")){if(!result.next()||!DB.equals(result.getString(1))||!DB.equals(result.getString(2)))throw new IllegalStateException("Unexpected physical database/user");}catch(java.sql.SQLException error){throw new IllegalStateException("Cannot verify physical private target",error);}
  }
  public void initialize(org.springframework.context.ConfigurableApplicationContext context){context.addBeanFactoryPostProcessor(factory->{
   config(context.getEnvironment());audit("early-non-eager-singletons",factory,false,false);audit("early-declared-types",factory,true,false);
   factory.addBeanPostProcessor(new org.springframework.beans.factory.config.BeanPostProcessor(){
    @Override public Object postProcessBeforeInitialization(Object bean,String name){
     if(bean instanceof javax.sql.DataSource)dataSource(bean);
     if(bean instanceof org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory redis&&(redis.getDatabase()!=15||!"127.0.0.1".equals(redis.getHostName())||redis.getPort()!=26379))throw new IllegalStateException("Unexpected pre-initialization Redis target");
     // Resolve FactoryBean products only here, before resolver @PostConstruct or ADMIN runner work.
     if(bean instanceof com.h.vanioak.modules.alert.internal.engine.AlertResolutionScheduler||bean instanceof org.springframework.boot.ApplicationRunner)verified(factory);
     return bean;
    }
   });
  });}
 }

	@LocalServerPort int port;
	@Autowired ObjectMapper json;
	@Autowired EntityManager em;
	@Autowired StringRedisTemplate redis;
	@Autowired ConnectionFactory connection;
	@Autowired com.h.vanioak.modules.alert.api.AlertEngineFacade engine;
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
 @Autowired javax.sql.DataSource datasource;
 @Autowired org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory redisFactory;
 @MockitoSpyBean RealtimeService realtime;
 record Observation(RealtimeFacade.IncidentEvent event,RealtimeFacade.Fanout fanout){}
 final List<Observation> observations=new CopyOnWriteArrayList<>();
 AlertIncidentSseNativeIT.Probe probe;boolean stopVerified;
 @BeforeEach void observe(){doAnswer(call->{var e=(RealtimeFacade.IncidentEvent)call.getArgument(0);var result=(RealtimeFacade.Fanout)call.callRealMethod();observations.add(new Observation(e,result));return result;}).when(realtime).publishIncident(any());}
 void awaitRegistration() throws Exception {
  long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
  while(System.nanoTime()<end){if(realtime.ready()){var list=broker("consumers/processing-test",end);assertTrue(list.size()<=1);if(list.size()==1){var c=list.get(0);Channel channel=(Channel)ReflectionTestUtils.getField(rtConsumer,"sourceChannel");assertNotNull(channel);assertTrue(channel.isOpen());assertEquals("realtime.queue",c.get("queue").get("name").asString());assertEquals("processing-test",c.get("queue").get("vhost").asString());assertEquals(ReflectionTestUtils.getField(rtConsumer,"tag"),c.get("consumer_tag").asString());assertEquals(channel.getChannelNumber(),c.get("channel_details").get("number").asInt());assertTrue(c.get("exclusive").asBoolean()&&c.get("active").asBoolean()&&c.get("ack_required").asBoolean());assertEquals(1,c.get("prefetch_count").asInt());for(JsonNode row:broker("connections",end))if(row.get("name").asString().equals(c.get("channel_details").get("connection_name").asString())){assertEquals(channel.getConnection().getClientProvidedName(),row.get("client_properties").get("connection_name").asString());assertTrue(System.nanoTime()<end);save("registration.json",list);return;}}}Thread.sleep(100);}throw new AssertionError("Registration deadline");
 }
 static AlertIncidentSseNativeIT.Frame parseResolved(String block,ObjectMapper mapper){
  String name=null,data=null;for(String line:block.replace("\r\n","\n").split("\n")){if(line.isEmpty()||line.startsWith(":"))continue;if(line.startsWith("event:")&&name==null)name=line.substring(6).stripLeading();else if(line.startsWith("data:")&&data==null)data=line.substring(5).stripLeading();else throw new IllegalArgumentException("Unexpected SSE field");}
  if(name==null&&data==null)return null;if(!"incident.resolved".equals(name)||data==null)throw new IllegalArgumentException("Unexpected resolved frame");var node=mapper.readTree(data);if(!node.isObject()||node.size()!=3||!node.has("incident_id")||!node.has("status")||!node.has("resolved_at")||!node.get("incident_id").isString()||!node.get("resolved_at").isString()||!"RESOLVED".equals(node.get("status").asString()))throw new IllegalArgumentException("Invalid resolved payload");UUID.fromString(node.get("incident_id").asString());Instant.parse(node.get("resolved_at").asString());return new AlertIncidentSseNativeIT.Frame(name,node);
 }
 static void assertResolved(List<AlertIncidentSseNativeIT.Frame> frames,UUID incident,Instant time){assertEquals(1,frames.size());var frame=frames.get(0);assertEquals("incident.resolved",frame.name());assertEquals(3,frame.data().size());assertEquals(incident.toString(),frame.data().get("incident_id").asString());assertEquals("RESOLVED",frame.data().get("status").asString());assertEquals(time,Instant.parse(frame.data().get("resolved_at").asString()));}
 @Test @Timeout(120) void isolatedCommittedIncidentResolvesOnceOverSse() throws Exception {
  phase("startup");try(var c=datasource.getConnection();var s=c.createStatement();var rs=s.executeQuery("select current_database(),current_user")){assertTrue(rs.next());assertEquals(DB,rs.getString(1));assertEquals(DB,rs.getString(2));}assertEquals(15,redisFactory.getDatabase());assertEquals(0,((Number)em.createNativeQuery("select count(*) from incidents").getSingleResult()).intValue());awaitRegistration();phase("consumer_ready");
  var login=api("POST","/auth/login",Map.of("username",ADMIN,"password",PASSWORD),200,null);access=login.get("access_token").asString();refresh=login.get("refresh_token").asString();state.put("user_id",login.get("user").get("id").asString());
  var app=api("POST","/applications",Map.of("name","resolved-"+RUN),201);UUID appId=UUID.fromString(app.get("id").asString());state.put("application_id",appId);for(JsonNode e:app.get("environments"))if("DEV".equals(e.get("name").asString()))environment=UUID.fromString(e.get("id").asString());assertNotNull(environment);state.put("environment_id",environment);
  var rule=api("POST","/alert-rules",Map.of("environment_id",environment,"level","ERROR","threshold",1,"window_seconds",60,"cooldown_seconds",60,"resolve_after_seconds",30,"enabled",true),201);state.put("rule_id",rule.get("id").asString());
  UUID eventId=UUID.randomUUID();Instant received=Instant.now().truncatedTo(ChronoUnit.MICROS);long resolutionDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);var result=engine.evaluate(new com.h.vanioak.modules.alert.api.AlertEngineFacade.CriticalEvent(eventId,environment,com.h.vanioak.modules.alert.api.AlertRuleFacade.Level.ERROR,"Isolated resolved caf? ? "+RUN,received));assertEquals(com.h.vanioak.modules.alert.api.AlertEngineFacade.Decision.OPENED,result.decision());assertEquals(1,result.errorCount());state.put("event_id",eventId);state.put("incident_id",result.incidentId());
  var opened=database();save("postgres-open.json",opened);assertEquals(1,opened.get("receipts").size());assertEquals(1,opened.get("incidents").size());assertEquals(1,opened.get("history").size());assertEquals("OPEN",opened.get("incidents").get(0).get("status").asString());assertEquals("OPENED",opened.get("history").get(0).get("event_type").asString());assertTrue(opened.get("incidents").get(0).get("resolved_at").isNull());
  long createdEnd=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(observations.size()<1&&System.nanoTime()<createdEnd)Thread.sleep(20);assertEquals(1,observations.size());assertEquals("incident.created",observations.get(0).event().type());assertTrue(((List<?>)ReflectionTestUtils.getField(realtime,"owned")).isEmpty());
  String target="/realtime/stream?application_id="+appId+"&environment_id="+environment+"&level=ERROR";var future=http.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+target)).timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+access).header("Accept","text/event-stream").GET().build(),HttpResponse.BodyHandlers.ofInputStream());
  try{var response=future.get(22,TimeUnit.SECONDS);assertEquals(200,response.statusCode());assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));probe=new AlertIncidentSseNativeIT.Probe(response.body(),evidence().resolve("resolved-sse.txt"),json,AlertResolvedSseNativeIT::parseResolved);probe.start();}finally{if(!future.isDone())future.cancel(true);}
  assertTrue(Instant.now().isBefore(received.plusSeconds(25)),"Stream admitted too late");assertEquals(1,((List<?>)ReflectionTestUtils.getField(realtime,"owned")).size());state.put("sse_http_status",200);phase("streams_open");
  Map<String,JsonNode> resolved=null;while(System.nanoTime()<resolutionDeadline){assertNull(probe.failure.get());var current=database();if(current.get("incidents").get(0).get("status").asString().equals("RESOLVED")&&probe.frames.size()>=1){resolved=current;break;}Thread.sleep(100);}assertNotNull(resolved,"Resolution deadline");assertTrue(System.nanoTime()<resolutionDeadline);
  var incident=resolved.get("incidents").get(0);Instant resolvedAt=Instant.parse(incident.get("resolved_at").asString());assertFalse(resolvedAt.isBefore(received.plusSeconds(30)));assertEquals(1,incident.get("error_count").asLong());for(String field:List.of("id","environment_id","fingerprint","message","error_count","first_seen_at","last_seen_at"))assertEquals(opened.get("incidents").get(0).get(field),incident.get(field));assertEquals(1,resolved.get("receipts").size());assertEquals(2,resolved.get("history").size());assertEquals(opened.get("receipts"),resolved.get("receipts"));assertEquals(opened.get("history").get(0),resolved.get("history").get(0));var history=resolved.get("history").get(1);assertEquals("RESOLVED",history.get("event_type").asString());assertEquals(1,history.get("error_count").asLong());assertEquals(resolvedAt,Instant.parse(history.get("event_at").asString()));
  assertResolved(probe.capturedFrames(false),result.incidentId(),resolvedAt);Thread.sleep(3000);assertEquals(resolved,database());assertResolved(probe.capturedFrames(false),result.incidentId(),resolvedAt);assertEquals(2,observations.size());var observed=observations.get(1);assertEquals("incident.resolved",observed.event().type());assertEquals(history.get("id").asString(),observed.event().eventId().toString());assertEquals(resolvedAt,observed.event().occurredAt());assertEquals(appId,observed.event().applicationId());assertEquals(environment,observed.event().environmentId());assertEquals("alert",observed.event().producer());assertEquals(1,observed.event().schemaVersion());assertEquals("ERROR",observed.event().level());assertEquals("RESOLVED",observed.event().status());assertTrue(observed.fanout().accounted());assertEquals(1,observed.fanout().queued());save("facade-observations.json",observations);save("postgres-resolved.json",resolved);
  String key="vanioak:alert:frequency:"+environment+":"+incident.get("fingerprint").asString()+":"+rule.get("id").asString();var members=redis.opsForZSet().rangeWithScores(key,0,-1);assertNotNull(members);assertEquals(1,members.size());var member=members.iterator().next();assertEquals(eventId.toString(),member.getValue());assertEquals((double)(received.getEpochSecond()*1_000_000+received.getNano()/1000),member.getScore().doubleValue());assertTrue(redis.getExpire(key)>0);save("redis-frequency.json",Map.of("database",15,"key",key,"members",members,"ttl",redis.getExpire(key)));
  var detail=api("GET","/incidents/"+result.incidentId(),null,200);assertEquals("RESOLVED",detail.get("status").asString());save("incident-detail.json",detail);
  probe.beginPlannedStop();rtConsumer.stop();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<end&&(realtime.ready()||broker("consumers/processing-test",end).size()!=0||probe.reader.isAlive()))Thread.sleep(50);assertFalse(realtime.ready());assertEquals(0,broker("consumers/processing-test").size());assertFalse(probe.reader.isAlive());stopVerified=true;assertResolved(probe.capturedFrames(true),result.incidentId(),resolvedAt);state.put("outcome","PASS");phase("verified");
 }
 @AfterEach @Timeout(20) void cleanup() throws Exception {
  try{if(refresh!=null){api("POST","/auth/logout",Map.of("refresh_token",refresh),200);state.put("sessions_logged_out",1);}rtConsumer.close();if(probe!=null)probe.close();phase("cleanup_complete");}finally{http.shutdownNow();}
 }
}
