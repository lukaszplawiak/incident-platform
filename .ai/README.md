# AI Workspace

> This directory contains structured knowledge for AI assistants working on Incident Platform.
>
> The AI Workspace is treated as part of the production engineering system.
> Changes to this directory should follow the same review standards as source code changes.

---

# Purpose

The purpose of the AI Workspace is to provide reliable project context for AI-assisted development.

AI assistants cannot always infer architectural intent, business constraints, engineering decisions, or historical context from source code alone.

This directory stores knowledge that helps AI assistants:

- understand the system before making changes,
- preserve architectural consistency,
- avoid introducing unnecessary complexity,
- make decisions aligned with project principles,
- collaborate effectively across different development tasks.

The goal is not to replace source code documentation.

The goal is to document the knowledge that cannot be reliably extracted from the codebase.

---

# Documentation Philosophy

The AI Workspace follows these principles:

## 1. Context over prompts

Project knowledge is more valuable than generic AI instructions.

The quality of AI output depends primarily on the quality of context provided to the assistant.

---

## 2. Document intent, not implementation

Source code explains **how** the system works.

AI documentation explains:

- why decisions were made,
- what constraints exist,
- what principles must be preserved,
- what alternatives were rejected.

---

## 3. Documentation is part of development

A feature is not considered complete if it changes system knowledge without updating relevant AI documentation.

Examples:

- new architectural pattern → update architecture context,
- new security rule → update security context,
- important technical decision → create ADR.

---

# Workspace Structure

The AI Workspace is introduced incrementally.

Not all directories below may exist yet.

The target structure:

```text
.ai/

├── README.md

├── context/
│   ├── project.md
│   ├── architecture.md
│   ├── backend.md
│   ├── frontend.md
│   ├── security.md
│   └── infrastructure.md
│
├── rules/
│   ├── engineering.md
│   ├── backend.md
│   ├── frontend.md
│   └── testing.md
│
├── decisions/
│   └── adr-index.md
│
├── agents/
│   ├── architect.md
│   ├── backend-developer.md
│   ├── frontend-developer.md
│   └── reviewer.md
│
├── playbooks/
│   ├── new-feature.md
│   ├── bug-fix.md
│   └── refactoring.md
│
├── workflows/
│   ├── feature-development.md
│   └── pull-request-review.md
│
└── reviews/
    ├── architecture-review.md
    └── code-review.md
```

Directories will be created when they provide real value.

---

# Reading Order

Before making any implementation decision, AI assistants should follow this order:

## Step 1 — Understand the system

Read:

```
.ai/context/project.md
.ai/context/architecture.md
```

---

## Step 2 — Understand the technology area

Depending on the task:

Backend:

```
.ai/context/backend.md
```

Frontend:

```
.ai/context/frontend.md
```

Infrastructure:

```
.ai/context/infrastructure.md
```

Security-related work:

```
.ai/context/security.md
```

---

## Step 3 — Check constraints

Read relevant rules:

```
.ai/rules/
```

---

## Step 4 — Check previous decisions

Before introducing a new architectural approach:

```
.ai/decisions/
```

Existing decisions should be respected unless explicitly changed.

---

# Rules for AI Assistants

AI assistants working on Incident Platform should:

- understand existing architecture before proposing changes,
- prefer consistency over introducing new technologies,
- avoid unnecessary abstractions,
- avoid changing unrelated code,
- preserve security principles,
- consider backward compatibility,
- explain architectural trade-offs,
- identify risks before implementation.

AI assistants should not:

- introduce frameworks without justification,
- rewrite working code without clear benefit,
- create abstractions only for theoretical future needs,
- ignore existing architectural decisions.

---

# Core Engineering Principles

Incident Platform follows these principles:

## Simplicity

Prefer simple solutions that are easy to understand and maintain.

Complexity must have a clear business or technical justification.

---

## Explicit Architecture

Architectural boundaries, responsibilities and decisions should be visible.

Hidden complexity is considered technical debt.

---

## Security by Default

Security requirements are considered during design, not added afterwards.

---

## Production Mindset

Implementation decisions should consider:

- scalability,
- observability,
- failure scenarios,
- maintainability,
- operational impact.

---

## Incremental Evolution

The system evolves through small, controlled changes.

Avoid large rewrites unless there is a strong architectural reason.

---

# Updating AI Knowledge

When making changes to Incident Platform:

Ask:

> "Does this change introduce knowledge that an AI assistant cannot reliably infer from source code?"

If yes, update `.ai`.

Examples:

| Change | Update |
|---|---|
| New service | architecture.md |
| New technology decision | ADR |
| New coding convention | rules |
| New business workflow | context |
| New development process | playbooks/workflows |

---

# Current AI Workspace Status

Current phase:

```
Phase 1 - Context Foundation
```

Implemented:

- AI Workspace structure
- Documentation principles
- Initial project context

Not implemented yet:

- specialized agents,
- automated workflows,
- development playbooks,
- AI review processes.

These will be introduced incrementally as the project evolves.