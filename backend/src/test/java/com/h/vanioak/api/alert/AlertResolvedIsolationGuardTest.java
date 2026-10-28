package com.h.vanioak.api.alert;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.orm.jpa.EntityManagerFactoryInfo;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import jakarta.persistence.EntityManagerFactory;
import com.zaxxer.hikari.HikariDataSource;

class AlertResolvedIsolationGuardTest {
 static class LateFactory implements FactoryBean<EntityManagerFactory>,InitializingBean {
  final EntityManagerFactory value;boolean initialized;
  LateFactory(EntityManagerFactory value){this.value=value;}
  public void afterPropertiesSet(){initialized=true;}
  public EntityManagerFactory getObject(){return value;}
  public Class<?> getObjectType(){return initialized?EntityManagerFactory.class:null;}
 }
 static class Writer implements ApplicationRunner,InitializingBean {
  final AtomicBoolean initialized;Writer(AtomicBoolean initialized){this.initialized=initialized;}
  public void afterPropertiesSet(){initialized.set(true);}public void run(ApplicationArguments args){}
 }
 static class Fixture implements AutoCloseable {
  final GenericApplicationContext context=new GenericApplicationContext();
  final AlertResolvedSseNativeIT.IsolationGuard guard=new AlertResolvedSseNativeIT.IsolationGuard();
  final HikariDataSource ds=mock(HikariDataSource.class),other=mock(HikariDataSource.class);
  final EntityManagerFactory emf=mock(EntityManagerFactory.class,withSettings().extraInterfaces(EntityManagerFactoryInfo.class));
  final LettuceConnectionFactory redis=mock(LettuceConnectionFactory.class);
  final AtomicBoolean writerInitialized=new AtomicBoolean();
  Fixture() throws Exception {
   var properties=new HashMap<String,Object>();properties.put("spring.datasource.url",AlertResolvedSseNativeIT.URL);properties.put("spring.datasource.username",AlertResolvedSseNativeIT.DB);properties.put("spring.data.redis.database","15");properties.put("spring.flyway.url",AlertResolvedSseNativeIT.URL);properties.put("spring.flyway.user",AlertResolvedSseNativeIT.DB);properties.put("spring.flyway.enabled","false");properties.put("spring.jpa.hibernate.ddl-auto","validate");properties.put("spring.sql.init.mode","never");context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("private-test",properties));
   for(var source:List.of(ds,other)){when(source.getJdbcUrl()).thenReturn(AlertResolvedSseNativeIT.URL);when(source.getUsername()).thenReturn(AlertResolvedSseNativeIT.DB);}
   var info=(EntityManagerFactoryInfo)emf;when(info.getDataSource()).thenReturn(ds);when(info.getNativeEntityManagerFactory()).thenReturn(emf);
   when(redis.getDatabase()).thenReturn(15);when(redis.getHostName()).thenReturn("127.0.0.1");when(redis.getPort()).thenReturn(26379);
   var connection=mock(java.sql.Connection.class);var statement=mock(java.sql.Statement.class);var result=mock(java.sql.ResultSet.class);when(ds.getConnection()).thenReturn(connection);when(connection.createStatement()).thenReturn(statement);when(statement.executeQuery("select current_database(),current_user")).thenReturn(result);when(result.next()).thenReturn(true);when(result.getString(1)).thenReturn(AlertResolvedSseNativeIT.DB);when(result.getString(2)).thenReturn(AlertResolvedSseNativeIT.DB);
   context.registerBean("dataSource",HikariDataSource.class,()->ds);
   context.registerBean("entityManagerFactory",LateFactory.class,()->new LateFactory(emf));
   context.registerBean("transactionManager",JpaTransactionManager.class,()->{var tx=new JpaTransactionManager(context.getBean(EntityManagerFactory.class));tx.setDataSource(ds);return tx;});
   context.registerBean("redisConnectionFactory",LettuceConnectionFactory.class,()->redis);
   context.registerBean("writer",Writer.class,()->new Writer(writerInitialized));guard.initialize(context);
  }
  void reject(){assertThrows(RuntimeException.class,context::refresh);assertFalse(writerInitialized.get());}
  public void close(){context.close();}
 }
 @Test void reproducesEarlyFactoryProductOmissionAndVerifiesBeforeWriter() throws Exception {
  try(var f=new Fixture()){f.context.refresh();assertTrue(f.writerInitialized.get());System.out.println("Offline Spring isolation phases: "+f.guard.diagnostics);var early=f.guard.diagnostics.get(0).get("beans");assertTrue(early.toString().contains("EntityManagerFactory=[]"));assertTrue(f.guard.diagnostics.stream().anyMatch(d->d.get("phase").equals("before-business-initialization")&&d.get("beans").toString().contains("entityManagerFactory")));}
 }
 @Test void rejectsHistoricalPropertiesBeforeDatasourceCreation() throws Exception {
  try(var f=new Fixture()){f.context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("wrong",Map.of("spring.datasource.url","jdbc:postgresql://127.0.0.1:25432/processing_test")));f.reject();verify(f.ds,never()).getConnection();}
 }
 @Test void rejectsActualWrongDatasourceBeforeConnectionOrWriter() throws Exception {
  try(var f=new Fixture()){when(f.ds.getJdbcUrl()).thenReturn("jdbc:postgresql://127.0.0.1:25432/processing_test");f.reject();verify(f.ds,never()).getConnection();}
 }
 @Test void rejectsAdditionalDatasourceAndRetainsDiagnostics() throws Exception {
  try(var f=new Fixture()){f.context.registerBean("extraSource",HikariDataSource.class,()->f.other);f.reject();assertTrue(f.guard.diagnostics.toString().contains("extraSource"));}
 }
 @Test void rejectsWrongJpaDatasourceAssociation() throws Exception {
  try(var f=new Fixture()){when(((EntityManagerFactoryInfo)f.emf).getDataSource()).thenReturn(f.other);f.reject();}
 }
 @Test void rejectsWrongTransactionDatasourceAssociation() throws Exception {
  try(var f=new Fixture()){f.context.removeBeanDefinition("transactionManager");f.context.registerBean("transactionManager",JpaTransactionManager.class,()->{var tx=mock(JpaTransactionManager.class);when(tx.getEntityManagerFactory()).thenReturn(f.emf);when(tx.getDataSource()).thenReturn(f.other);return tx;});f.reject();}
 }
 @Test void rejectsRedisZeroBeforeInitialization() throws Exception {
  try(var f=new Fixture()){when(f.redis.getDatabase()).thenReturn(0);f.reject();verify(f.redis,never()).afterPropertiesSet();}
 }
 @Test void rejectsMigrationOrDdlOverride() throws Exception {
  for(var override:List.of(Map.of("spring.flyway.enabled","true"),Map.of("spring.jpa.hibernate.ddl-auto","update"),Map.of("spring.sql.init.mode","always"))){try(var f=new Fixture()){f.context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("unsafe",new HashMap<>(override)));f.reject();verify(f.ds,never()).getConnection();}}
 }
 @Test void rejectsPhysicalDatabaseMismatchBeforeWriter() throws Exception {
  try(var f=new Fixture()){when(f.ds.getConnection().createStatement().executeQuery("select current_database(),current_user").getString(1)).thenReturn("processing_test");f.reject();}
 }
}
