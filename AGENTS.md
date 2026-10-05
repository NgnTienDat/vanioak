# Coding Agent Instructions

## Start Here

1. Read `.agents/context/project-overview.md` first.
2. Read only the applicable rules under `.agents/rules/`.
3. Use `docs/README.md` to locate authoritative project documentation.
4. Load only documents relevant to the task; do not load the entire docs tree.

Project truth lives under `docs/`. The `.agents/` tree contains agent
operating instructions, not project specifications.

## Select Rules

| Task | Rule |
|---|---|
| Architecture or module boundaries | `.agents/rules/architecture.md` |
| REST or SSE implementation | `.agents/rules/api-design.md` |
| Code implementation or refactoring | `.agents/rules/coding-style.md` |
| Persistence or schema work | `.agents/rules/database.md` |
| Authentication, authorization, or secrets | `.agents/rules/security.md` |
| Tests or verification | `.agents/rules/testing.md` |

## Inspect Before Changing

- Architecture: `docs/architecture/architecture.md` and relevant flow/failure docs.
- Module boundaries: the owning spec under `docs/modules/` and its dependency matrix.
- REST/SSE: `docs/contracts/openapi.yaml` and `docs/contracts/sse-api.md`.
- RabbitMQ: `docs/contracts/event-contracts.md` and `docs/architecture/rabbitmq-topology.md`.
- Database schemas: the applicable SQL contract under `docs/contracts/`.
- Security boundaries: `docs/quality/security.md`.
- MVP scope: `docs/product/mvp-scope.md`.

Do not invent undocumented behavior or make broad refactors or unrelated changes.
When required behavior is ambiguous or a request conflicts with project docs,
stop and ask instead of silently redesigning.

## Skills

Skill locations are `.agents/skills/writing-plans/`,
`.agents/skills/executing-plans/`, and `.agents/skills/fix/`.
Their behavior belongs in their own `SKILL.md` files, not this router.

## Choose a Workflow

| Situation | Use |
|---|---|
| New feature, or change that touches >3 files, a contract, schema, security boundary, or MVP scope | `writing-plans` → wait for user approval → `executing-plans` |
| Reported defect | `fix` (escalates to `writing-plans` if the fix is large) |
| Small, clear edit (typo, rename, one-file tweak) | Do it directly, no skill |

Skills live in `.agents/skills/<name>/SKILL.md`; their behavior is defined there,
not in this file. Plans are saved under `docs/plans/` and are not project truth.

## Stop and Ask Before

- Changing an API/event contract, database schema, or security boundary.
- Adding a dependency.
- Deleting files or running destructive commands.
- Working outside `docs/product/mvp-scope.md`.

Report in the user's language. Never claim a check passed unless it was run.
