---
name: executing-plans
description: Implement a plan in docs/plans/ whose Status is Approved, in scoped steps with verification; use only when an approved plan exists, not to design or redesign.
---

# Executing Plans

## Context and Boundaries

Paths are repository-root relative. Follow `AGENTS.md` and
`.agents/context/project-overview.md`; load only applicable rules and relevant docs.
`docs/` is authoritative for project behavior; `.agents/rules/` for coding constraints.
This skill defines workflow, not architecture or contracts.
Stop and ask when the request conflicts with authoritative documentation.

## Workflow

1. Locate the plan in `docs/plans/`. Proceed only if `Status: Approved`. If missing, Draft, or ambiguous, stop and ask; never treat the original task request as approval.
2. Before changing code, re-read only applicable rules and the relevant docs/contracts.
3. Follow the plan in order. If repository reality invalidates a step, or the plan conflicts with current code, docs, contracts, or security rules, stop and report; do not improvise a redesign.
4. Make small, scoped changes; leave unrelated files alone.
5. Add dependencies only when the approved plan requires them. Preserve public contracts unless their change was approved.
6. After each step, run focused verification where practical, then tick its checkbox in the plan. Record anything that differs from the plan in the Deviations Log.
7. After implementation, run relevant tests and broader affected checks when warranted.
8. Inspect the final diff for unrelated edits. Update docs/contracts only when the approved change requires it.
9. Never weaken tests, hide failures, or claim unrun checks passed. Separate environment blockers and pre-existing failures from regressions.
10. Set the plan to `Status: Done` only when every step is ticked and verified. Do not commit unless the user asks.

## Final Report

Write in the user's language, using these sections:

```markdown
# Implemented
# Verification
# Deviations
# Remaining Issues
```

Do not invent remaining issues when the plan and verification succeeded.