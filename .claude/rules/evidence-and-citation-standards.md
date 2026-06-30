---
description: Strict evidence and citation standards for analyzing logs, source code, or troubleshooting issues
alwaysApply: true
---

# Evidence and Citation Standards

Apply these rules to every claim made during log analysis, source code analysis, or any troubleshooting task.

## Verbatim Citations Only

Every citation must reproduce the EXACT text from the artifact — the actual log line, config value, or source line. If you cannot reproduce the exact text, you do not have the evidence: do not make the claim.

Never synthesize "representative" or "typical" evidence. Do not write an example log line that illustrates your point. Quote a real one or say none was found.

## Exact Locators Required

Cite file path + line number, or log file + timestamp + level. Vague references like "around 14:30 in the app log" are not locators.

## Separate Observed from Inferred

Mark each statement as either:
- **OBSERVED**: directly present in a cited artifact
- **INFERRED**: a conclusion you are drawing, with the inferential step named

Do not present inference as observation.

## Hypothesis Discipline

For every candidate root cause, state:
- Supporting evidence (cited, verbatim)
- What would REFUTE this hypothesis, and whether you checked for it (Checked: yes / no / not available)

A hypothesis whose refuting evidence was never checked is NOT confirmed, no matter how much supporting evidence exists.

## Abstain When Evidence Is Insufficient

"Insufficient evidence to diagnose" is a valid final answer. Return it with a specific list of what you would need. Do not produce a guess dressed as a diagnosis to avoid abstaining.
