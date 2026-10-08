package com.h.vanioak.api.identity.dto;

import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.h.vanioak.modules.identity.api.ApplicationFacade.Status;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpdateApplicationRequest {
	@Size(max = 100)
	@JsonSetter(nulls = Nulls.FAIL)
	private String name;
	@JsonSetter(nulls = Nulls.FAIL)
	private String description;
	@JsonSetter(nulls = Nulls.FAIL)
	private Status status;
}

