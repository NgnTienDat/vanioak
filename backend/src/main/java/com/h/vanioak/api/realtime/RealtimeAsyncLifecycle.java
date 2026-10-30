package com.h.vanioak.api.realtime;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Component
@Configuration // mình tự thêm vào lúc 30/10/26 - 9:23 A.M
public class RealtimeAsyncLifecycle implements AsyncHandlerInterceptor, WebMvcConfigurer {
	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(this).addPathPatterns(RealtimeController.PATH);
	}

	@Override
	public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response, Object handler) {
		Object connection = request.getAttribute(RealtimeController.CONNECTION);
		if (connection instanceof RealtimeSseConnection sse) sse.activate();
	}

	@Override
	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
		Object connection = request.getAttribute(RealtimeController.CONNECTION);
		if (connection instanceof RealtimeSseConnection sse) sse.abort();
	}
}
