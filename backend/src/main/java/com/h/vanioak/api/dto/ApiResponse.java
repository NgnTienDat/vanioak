package com.h.vanioak.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class ApiResponse<T> {

	private boolean success;
	private String message;
	private T data;

	public static <T> ApiResponse<T> success(T data) {
		return success(data, "Success");
	}

	public static <T> ApiResponse<T> success(T data, String message) {
		return ApiResponse.<T>builder().success(true).message(message).data(data).build();
	}

	public static <T> ApiResponse<T> error(String message) {
		return ApiResponse.<T>builder().success(false).message(message).build();
	}
}
