package com.h.vanioak.modules.alert.internal.rule;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.h.vanioak.modules.alert.api.AlertException;
import com.h.vanioak.modules.alert.api.AlertRuleFacade;
import com.h.vanioak.modules.alert.api.ErrorCode;
import lombok.RequiredArgsConstructor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AlertRuleService implements AlertRuleFacade {
	private final AlertRuleRepository rules;

	@Override @Transactional
	public Rule create(UUID createdBy, Create command) {
		if (createdBy == null || command == null || command.environmentId() == null
				|| command.level() == null || command.enabled() == null)
			throw new AlertException(ErrorCode.INVALID_REQUEST);
		requiredPositive(command.threshold(), command.windowSeconds(), command.cooldownSeconds(), command.resolveAfterSeconds());
		try {
			return view(rules.saveAndFlush(new AlertRuleEntity(createdBy, command)));
		} catch (DataIntegrityViolationException ex) {
			for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
				if (cause instanceof ConstraintViolationException violation
						&& "23505".equals(violation.getSQLState())
						&& "uq_alert_rules_scope_level".equals(violation.getConstraintName()))
					throw new AlertException(ErrorCode.RULE_ALREADY_EXISTS);
			}
			throw ex;
		}
	}

	@Override
	public Page list(String cursor, int limit) {
		if (limit < 1 || limit > 100) throw new AlertException(ErrorCode.INVALID_REQUEST);
		UUID after = null;
		if (cursor != null) {
			try {
				if (cursor.length() > 64) throw new IllegalArgumentException();
				String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
				after = UUID.fromString(value);
				if (!after.toString().equals(value)) throw new IllegalArgumentException();
			} catch (IllegalArgumentException ex) { throw new AlertException(ErrorCode.INVALID_CURSOR); }
		}
		var rows = rules.findRules(after, PageRequest.of(0, limit + 1));
		var items = rows.stream().limit(limit).map(this::view).toList();
		String next = rows.size() > limit ? Base64.getUrlEncoder().withoutPadding()
				.encodeToString(items.getLast().id().toString().getBytes(StandardCharsets.UTF_8)) : null;
		return new Page(items, next);
	}

	@Override
	public Rule get(UUID id) {
		return view(rules.findById(id).orElseThrow(() -> new AlertException(ErrorCode.RULE_NOT_FOUND)));
	}

	@Override @Transactional
	public Rule update(UUID id, Update command) {
		if (command == null) throw new AlertException(ErrorCode.INVALID_REQUEST);
		for (Integer value : new Integer[]{command.threshold(), command.windowSeconds(), command.cooldownSeconds(), command.resolveAfterSeconds()})
			if (value != null && value < 1) throw new AlertException(ErrorCode.INVALID_REQUEST);
		var rule = rules.findLocked(id).orElseThrow(() -> new AlertException(ErrorCode.RULE_NOT_FOUND));
		rule.update(command);
		rules.flush();
		return view(rule);
	}

	@Override @Transactional
	public Rule disable(UUID id) {
		return update(id, new Update(null, null, null, null, false));
	}

	private void requiredPositive(Integer... values) {
		for (Integer value : values)
			if (value == null || value < 1) throw new AlertException(ErrorCode.INVALID_REQUEST);
	}

	private Rule view(AlertRuleEntity rule) {
		return new Rule(rule.getId(), rule.getEnvironmentId(), rule.getLevel(), rule.getThreshold(),
				rule.getWindowSeconds(), rule.getCooldownSeconds(), rule.getResolveAfterSeconds(), rule.isEnabled(),
				rule.getCreatedAt(), rule.getUpdatedAt());
	}
}
