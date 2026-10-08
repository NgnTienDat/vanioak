package com.h.vanioak.api.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.h.vanioak.api.dto.ApiResponse;

import tools.jackson.databind.json.JsonMapper;

class GlobalExceptionHandlerTest {

	private final JsonMapper mapper = JsonMapper.builder().build();

	@Test
	void serializesSharedEnvelopeAndFactories() {
		assertEquals(mapper.readTree("{\"success\":false,\"message\":\"Failure\",\"data\":null}"),
				mapper.readTree(mapper.writeValueAsString(ApiResponse.error("Failure"))));
		assertEquals(mapper.readTree("{\"success\":true,\"message\":\"Success\",\"data\":\"payload\"}"),
				mapper.readTree(mapper.writeValueAsString(ApiResponse.success("payload"))));
		assertEquals(mapper.readTree("{\"success\":true,\"message\":\"OK\",\"data\":\"payload\"}"),
				mapper.readTree(mapper.writeValueAsString(ApiResponse.success("payload", "OK"))));
	}

	@Test
	void validationReturnsBadRequestEnvelope() throws Exception {
		var bindingResult = new BeanPropertyBindingResult(new Object(), "request");
		bindingResult.addError(new FieldError("request", "password", "secret-value", false,
				null, null, "invalid password"));
		bindingResult.addError(new ObjectError("request", "object-level error"));
		var parameter = new MethodParameter(FailingController.class.getDeclaredMethod("fail"), -1);
		String json = MockMvcBuilders.standaloneSetup(new FailingController(
				new MethodArgumentNotValidException(parameter, bindingResult)))
				.setControllerAdvice(new GlobalExceptionHandler()).build()
				.perform(get("/test")).andExpect(status().isBadRequest())
				.andReturn().getResponse().getContentAsString();
		var body = mapper.readTree(json);
		assertEquals(mapper.readTree("{\"success\":false,\"message\":\"Validation failed\","
				+ "\"data\":{\"password\":\"invalid password\"}}"), body);
		assertFalse(json.contains("secret-value"));
	}

	@Test
	void objectLevelValidationReturnsEmptyMap() throws Exception {
		var bindingResult = new BeanPropertyBindingResult(new Object(), "request");
		bindingResult.addError(new ObjectError("request", "object-level error"));
		var parameter = new MethodParameter(FailingController.class.getDeclaredMethod("fail"), -1);
		String json = MockMvcBuilders.standaloneSetup(new FailingController(
				new MethodArgumentNotValidException(parameter, bindingResult)))
				.setControllerAdvice(new GlobalExceptionHandler()).build()
				.perform(get("/test")).andExpect(status().isBadRequest())
				.andReturn().getResponse().getContentAsString();
		assertEquals(mapper.readTree("{\"success\":false,\"message\":\"Validation failed\",\"data\":{}}"),
				mapper.readTree(json));
	}

	@Test
	void unexpectedFailureReturnsSafeInternalErrorEnvelope() throws Exception {
		String json = MockMvcBuilders.standaloneSetup(new FailingController(
				new IllegalStateException("database password=secret-value")))
				.setControllerAdvice(new GlobalExceptionHandler()).build()
				.perform(get("/test")).andExpect(status().isInternalServerError())
				.andReturn().getResponse().getContentAsString();
		var body = mapper.readTree(json);
		assertEquals(mapper.readTree("{\"success\":false,\"message\":\"An unexpected error occurred\","
				+ "\"data\":null}"), body);
		assertFalse(json.contains("secret-value"));
		assertFalse(json.contains("IllegalStateException"));
	}

	@RestController
	static class FailingController {
		private final Exception failure;

		FailingController(Exception failure) {
			this.failure = failure;
		}

		@GetMapping("/test")
		public String fail() throws Exception {
			throw failure;
		}
	}
}
