package com.h.vanioak.modules.identity.internal.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.List;
import java.util.UUID;
import java.util.Locale;
import java.util.Objects;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.h.vanioak.modules.identity.api.ApplicationAccessFacade;
import com.h.vanioak.modules.identity.api.ApplicationFacade;
import com.h.vanioak.modules.identity.api.UserFacade;
import com.h.vanioak.modules.identity.internal.application.ApplicationService;
import com.h.vanioak.modules.identity.internal.user.UserService;
import com.h.vanioak.common.security.SecurityConfig;

@DataJpaTest(showSql=false,properties={"spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({ApplicationAccessService.class,ApplicationService.class,UserService.class,SecurityConfig.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class ApplicationAccessPersistenceIT {
	@DynamicPropertySource static void isolated(DynamicPropertyRegistry p) {
		if (!"true".equals(System.getenv("RBAC_TEST_SCOPE_APPROVED")) || !"jdbc:postgresql://127.0.0.1:25432/processing_test".equals(System.getenv("PROCESSING_POSTGRES_TEST_URL"))) throw new IllegalStateException("Require explicit isolated RBAC fixture");
		for(String k:List.of("URL","USERNAME","PASSWORD"))p.add("spring.datasource."+k.toLowerCase(Locale.ROOT),()->Objects.requireNonNull(System.getenv("PROCESSING_POSTGRES_TEST_"+k)));
	}
	@Autowired ApplicationAccessFacade access;
	@Autowired ApplicationFacade applications;
	@Autowired UserFacade users;
	@Autowired UserApplicationAccessRepository repository;
	@Autowired EntityManager entityManager;
	@Autowired PlatformTransactionManager transactions;
	@Test @Timeout(45) void concurrentGrantPreservesTimestampAndSqlScopedPagination() throws Exception {
		UUID actor=UUID.randomUUID();var tx=new TransactionTemplate(transactions);
		tx.executeWithoutResult(s->entityManager.createNativeQuery("INSERT INTO users(id,username,password_hash,role,status) VALUES (:id,:name,'unused-test-only-hash','ADMIN','ACTIVE')")
			.setParameter("id",actor).setParameter("name","rbac-persistence-"+actor).executeUpdate());
		var engineer=users.create(new UserFacade.CreateUser("rbac-engineer-"+UUID.randomUUID(),UUID.randomUUID().toString()));
		var apps=new ArrayList<ApplicationFacade.ApplicationView>();
		for(int i=0;i<3;i++)apps.add(applications.create(new ApplicationFacade.CreateApplication("rbac-persistence-"+UUID.randomUUID(),null)));
		apps.sort(Comparator.comparing(a->a.id().toString()));UUID first=apps.getFirst().id(),last=apps.getLast().id();
		var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
		try {
			var a=pool.submit(()->{start.await();return access.assign(actor,first,engineer.id());});
			var b=pool.submit(()->{start.await();return access.assign(actor,first,engineer.id());});start.countDown();
			assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
		} finally {pool.shutdownNow();}
		var key=new UserApplicationAccessId(engineer.id(),first);var created=repository.findById(key).orElseThrow().getCreatedAt();assertNotNull(created);
		access.assign(actor,first,engineer.id());assertEquals(created,repository.findById(key).orElseThrow().getCreatedAt());
		access.assign(actor,last,engineer.id());
		var page=applications.list(engineer.id(),null,null,1);assertEquals(first,page.items().getFirst().id());assertNotNull(page.nextCursor());
		var next=applications.list(engineer.id(),null,page.nextCursor(),1);assertEquals(last,next.items().getFirst().id());assertNull(next.nextCursor());
		assertEquals(3,page.items().getFirst().environments().size());
		access.revoke(actor,first,engineer.id());access.revoke(actor,first,engineer.id());assertFalse(repository.existsById(key));
		assertTrue(repository.existsByUserIdAndApplicationId(engineer.id(),last));
		assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->tx.executeWithoutResult(s->repository.assign(UUID.randomUUID(),first)));
	}
}
