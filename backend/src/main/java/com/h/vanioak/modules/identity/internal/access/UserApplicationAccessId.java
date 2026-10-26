package com.h.vanioak.modules.identity.internal.access;

import java.io.Serializable;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class UserApplicationAccessId implements Serializable {
	private static final long serialVersionUID = 1L;
	private UUID userId;
	private UUID applicationId;
}
