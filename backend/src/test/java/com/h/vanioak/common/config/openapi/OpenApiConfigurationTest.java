package com.h.vanioak.common.config.openapi;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springdoc.webmvc.ui.SwaggerConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.h.vanioak.api.identity.UserController;
import com.h.vanioak.api.identity.AuthController;
import com.h.vanioak.api.identity.ApplicationController;
import com.h.vanioak.modules.identity.api.ApplicationFacade;
import com.h.vanioak.api.identity.ApiKeyController;
import com.h.vanioak.modules.identity.api.ApiKeyFacade;
import com.h.vanioak.api.identity.security.IdentitySecurityConfig;
import com.h.vanioak.modules.identity.api.AuthFacade;
import com.h.vanioak.modules.identity.api.UserFacade;
import com.h.vanioak.api.ingestion.IngestionController;
import com.h.vanioak.api.ingestion.security.IngestionSecurityConfig;
import com.h.vanioak.modules.ingestion.api.IngestionFacade;

@WebMvcTest({UserController.class, AuthController.class, ApplicationController.class, ApiKeyController.class, IngestionController.class})
@Import({OpenApiConfiguration.class, IdentitySecurityConfig.class, IngestionSecurityConfig.class})
@ImportAutoConfiguration({ SpringDocConfiguration.class, SpringDocConfigProperties.class,
		SpringDocWebMvcConfiguration.class, SwaggerConfig.class,
		SwaggerUiConfigProperties.class, SwaggerUiOAuthProperties.class })
class OpenApiConfigurationTest {

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private UserFacade users;

	@MockitoBean
	private AuthFacade auth;
	@MockitoBean
	private ApplicationFacade applications;
	@MockitoBean
	private ApiKeyFacade keys;
	@MockitoBean
	private IngestionFacade ingestion;

	@Test
	void runtimeDocsDescribeImplementedApis() throws Exception {
		mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/v1/logs'].post.security[0].apiKeyAuth").isArray())
				.andExpect(jsonPath("$.paths['/api/v1/logs/batch'].post.security[0].apiKeyAuth").isArray())
				.andExpect(jsonPath("$.paths['/api/v1/logs'].post.responses['202']").exists())
				.andExpect(jsonPath("$.paths['/api/v1/logs/batch'].post.responses['202']").exists())
				.andExpect(jsonPath("$.paths['/api/v1/logs'].post.responses['200']").doesNotExist())
				.andExpect(jsonPath("$.paths['/api/v1/users']").exists())
				.andExpect(jsonPath("$.paths['/api/v1/applications'].get.security[0].bearerAuth").isArray())
				.andExpect(jsonPath("$.paths['/api/v1/applications'].post").exists())
				.andExpect(jsonPath("$.paths['/api/v1/applications/{applicationId}'].patch").exists())
				.andExpect(jsonPath("$.paths['/api/v1/applications/{applicationId}/environments/{environmentId}/api-keys'].post.security[0].bearerAuth").isArray())
				.andExpect(jsonPath("$.paths['/api/v1/api-keys/{credentialId}/rotate'].post").exists())
				.andExpect(jsonPath("$.paths['/api/v1/api-keys/{credentialId}'].delete").exists())
				.andExpect(jsonPath("$.paths['/api/v1/auth/login']").exists())
				.andExpect(jsonPath("$.paths['/api/v1/users'].get.security[0].bearerAuth").isArray())
				.andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.security").doesNotExist())
				.andExpect(jsonPath("$.paths['/api/v1/auth/refresh'].post.security").doesNotExist())
				.andExpect(jsonPath("$.paths['/api/v1/auth/logout'].post.security").doesNotExist())
				.andExpect(jsonPath("$.security").doesNotExist())
				.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
				.andExpect(jsonPath("$.components.securitySchemes.apiKeyAuth.name").value("X-API-Key"));
	}

	@Test
	void swaggerUiIsAvailable() throws Exception {
		mvc.perform(get("/swagger-ui.html").header(HttpHeaders.AUTHORIZATION, "Bearer malformed"))
				.andExpect(status().is3xxRedirection());
		mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
		verifyNoInteractions(auth);
	}
}
