---
description: coding guidelines and standards for all source files
alwaysApply: true
---

# Coding Guidelines

Full guidelines: `.docs/common/coding-guidelines.md` (read it when making non-trivial edits or when any rule below needs elaboration).

## Core Principles

- Follow established project conventions and the style of well-written existing code.
- Prefer consistency with the codebase over inventing a new local style.
- Favor readability and maintainability over cleverness or excessive concision.

## Naming

- Constants: `UPPERCASED_WITH_UNDERSCORES`
- Instance fields: `lowerCamelCase_` (trailing underscore)
- Static class fields: `UpperCamelCase_` (trailing underscore)
- No `m_`, leading underscores, or Hungarian notation
- Config attribute names must include measurement suffixes: `timeoutInSec`, `minThresholdInPct`

## Method and Class Design

- Keep methods short; prefer cyclomatic complexity ≤ 5
- Avoid mid-method `return` statements; use a single structured flow instead
- Avoid `continue` in loops; use an `if` block around the loop body instead
- Do not call overridable methods from constructors
- Getters must not mutate state

## Error Handling and Logging

- No empty `catch` blocks; if intentionally suppressed, explain why in a comment
- Declare checked exceptions for expected operational failures

## Configuration

- Never hardcode URLs, IP addresses, port numbers, or hostnames in source code; put them in config

## AI Agent Rules

- Do not generate dead, unused, unreachable, or speculative code
- Do not introduce helpers, abstractions, or wrappers beyond what the request requires
- Do not silently change behavior outside the requested scope
- Reuse existing utilities, naming patterns, and surrounding code style
- Keep edits minimal and localized for narrow requests
- Remove imports, variables, methods, and branches that become unused due to your change
- Do not invent APIs, config keys, or utility methods — use only what exists
- Preserve comments, documentation markers, and required file headers
- Avoid broad refactors unless explicitly requested
- When a local pattern conflicts with a guideline, follow the local pattern unless the task is to clean it up
- If a guideline seems counterproductive for the task at hand, follow it anyway and report concerns to the user
