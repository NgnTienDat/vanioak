---
name: writing-plans
description: Write and save an implementation plan for non-trivial repository work, then stop for approval; use before features or multi-file changes, not for trivial edits or for executing an approved plan.
---

# Writing Plans

## Context and Boundaries

Paths are repository-root relative. Follow `AGENTS.md` and
`.agents/context/project-overview.md`; load only applicable rules and relevant docs.
`docs/` is authoritative for project behavior; `.agents/rules/` for coding constraints.
Plans under `docs/plans/` are working records, never authoritative over docs or contracts.
This skill defines workflow, not architecture or contracts.
Stop and ask when the request conflicts with authoritative documentation.

## When a Plan Is Required

A plan is required if the task does any of the following:
- changes more than 3 files or crosses a module boundary;
- touches an API/event contract, database schema, security boundary, or MVP scope;
- adds a dependency.

Otherwise state that a formal plan is unnecessary and do not use this skill.

## Workflow

1. Understand the requested outcome and inspect existing code before deciding what changes.
2. Identify current behavior, affected modules/files, relevant contracts, risks, required checks, and non-goals.
3. Read only the project documents relevant to those findings. Do not invent details when evidence is missing; record the gap under Open Questions.
4. Flag any change to architecture, contracts, schemas, security, or MVP scope as requiring explicit approval, separate from implementation steps.
5. Write a small ordered plan. Each step states what changes, where, why, and how it is verified, as a checkbox.
6. Prefer the smallest change satisfying the task. Exclude unrelated cleanup.
7. Save the plan to `docs/plans/YYYY-MM-DD-<slug>.md` with `Status: Draft`.
8. Do not write code. End the turn by giving the plan path, listing open questions, and asking for explicit approval.
9. Never set `Status: Approved` yourself. Only do so after the user clearly confirms, and only then hand over to `executing-plans`.

## Plan Format

```markdown
Status: Draft | Approved | Done
Approved by / date: -

# Goal
# Relevant Sources
# Current State
# Plan
- [ ] 1. Change, location, reason, verification.
# Verification
# Risks / Open Questions
# Out of Scope
# Deviations Log
```

Write in the user's language. Do not repeat project docs inside the plan.