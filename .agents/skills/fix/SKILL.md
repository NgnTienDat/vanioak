---
name: fix
description: Diagnose and correct a scoped repository defect with focused verification; use for bug fixes, not feature work or broad redesign.
---

# Fix

## Context and Boundaries

Paths are repository-root relative. Follow `AGENTS.md` and
`.agents/context/project-overview.md`.
`docs/` is authoritative for project behavior; `.agents/rules/` is authoritative
for coding constraints. Load only relevant context, not every document.
This skill defines workflow, not architecture or contracts.
Keep the requested scope and prefer minimal, reviewable changes.
Stop and ask when the request conflicts with authoritative documentation.

## Workflow

1. Understand the reported symptom and expected behavior.
2. Reproduce the defect when practical; record reproduction limits without treating an unverified assumption as a finding.
3. Inspect the smallest relevant code path and identify the root cause before editing.
4. Read applicable agent rules and the relevant module docs/contracts before changing affected behavior.
5. Make the smallest correct fix; avoid unrelated refactors or added features.
6. Add or update a regression test where practical.
7. Run focused tests first, then broader affected checks when warranted.
8. Inspect the final diff for accidental changes and report checks actually run, failures, and remaining uncertainty.
9. Escalate instead of fixing when the root cause requires a contract, schema, or security change, spans more than one module, or exceeds the plan threshold in `writing-plans`. Report the root cause and hand over to `writing-plans`.

## Defect Guardrails

- Fix implementation to match its contract; do not change the contract to excuse a violation unless the user explicitly requested that change and required approval is established.
- Do not suppress exceptions or validation merely to remove the symptom.
- Do not disable retry, authorization, idempotency, or security behavior to make a case pass.
- For asynchronous defects, examine duplicate delivery, retry/redelivery, ACK timing, idempotency, and dependency failures.
- For authorization defects, test both allowed and forbidden access.
- For data defects, verify ownership and persistence behavior before considering schema changes.
- Follow `.agents/rules/testing.md`; do not weaken tests, hide failures, or claim unrun checks passed.

## Final Report

Keep these sections concise: Root Cause, Fix, Verification, and Remaining Risk.
Distinguish verified results from environment blockers and unresolved uncertainty.
Write in the user's language.
