# Coding Guidelines

## Purpose

This document consolidates team coding guidelines into a single reference for both human developers and generative AI coding assistants. The goal is consistency, readability, maintainability, and alignment with existing code and conventions.

## Guiding Principles

- Follow established project conventions and the style of well-written existing code.
- Prefer consistency with the existing codebase over inventing a new local style.
- Favor readability and maintainability over cleverness or excessive concision.
- Keep code predictable, explicit, and easy for the next developer to extend.
- Prefer designs that reduce hidden state, surprising side effects, and ambiguous behavior.

## Comments and Documentation

- Use JavaDoc-style comments for files, classes, methods, and member variables when appropriate.
- All public API released with the product must be fully documented.
- Comments must be grammatically correct and clearly written.
- All source files should include the standard header comment where required.
- Include `@author` where that is part of the team convention.
- Place class comments next to the class body, not before the package statement.
- Add comments for non-obvious design choices, tricky logic, complex mechanisms, workarounds, and assumptions.
- For special explanatory comments about tricky behavior, prefix them with developer initials, for example:
  - `KM: iterating through released orders only.`
- Where applicable, include a documentation marker that points to the related Directory resource or wiki page, for example:

- When modifying a class that should have a documentation marker, add or update it and make sure the related documentation stays in sync.

## Naming Conventions

### General Naming

- Use correct English for names whenever practical; use spellcheck when uncertain.
- Prefer descriptive names, even when they are longer.
- Literals should read naturally and make sense.
- Avoid ambiguous method names such as `maybeDoThat`; split them into a condition check and an action when appropriate.
- Name entities consistently with older names in the same domain.
  - Example: we already had existing tree implementation `RPTTreePersistingElement`, when implementing a table variant, do not introduce `RPTPersistingTableElement`, make it `RPTTablePersistingElement`.

### Java Type and Member Naming

- Constants must use `UPPERCASED_WITH_UNDERSCORES`.
- Interfaces should be prefixed with `I` when that matches the existing codebase convention.
- GUI visual classes should be prefixed with `V` when that matches existing conventions.
- Instance fields should start with a lowercase letter and end with `_`.
- Static class fields should start with an uppercase letter and end with `_`.
- Do not use `m_`, leading underscores, or Hungarian notation.
- Exception classes must end with `Exception`.

### Package and Prefix Conventions

- Place classes into packages in a consistent, logical way.
- Common layer classes go under `com.finsent.*`.
- Common GUI classes and packages go under `com.finsent.view.*`.
- Shared utility classes go under `com.finsent.util` or its subpackages.
- A subsystem, framework, service, or facility may deserve its own dedicated package.
- Package names should not use mixed case.
- Avoid overly long concatenated prefixes that reduce readability.
- Capitalize primary prefixes consistently, and use readable mixed-case secondary prefixes where appropriate.

### Resource and Config Naming

- Use resource naming in `PREFIXMixedCase.ext` form, not `mixedCase.ext`.
- Use `resource`, not `file`, in attribute or variable names that refer to resources.
- Do not encode resource format details in the attribute name unless necessary.
- Image files should use descriptive MixedCase names with the first letter capitalized.
- In config files, new attribute names should include measurement suffixes when applicable, for example:
  - `minThresholdInPct`
  - `timeoutInSec`
  - `timeoutInMillis`

## Method Design

- Keep methods short and readable.
- Prefer method cyclomatic complexity of 5 or less.
- Prefer structured methods with a clear control flow.
- Avoid multiple returns in methods. Returns, other than in the end of a method, are only acceptable when there are multiple conditions to be checked in the beginning, before running the method logic itself. See good and bad examples:
```
public void goodExample1()
{
    if (condition)
    {
        doSomething();
        doMore();
    }
    else
    {
        TMSystem.getLogFacility().error().write("Could not");    
    }
}

public void badExample1()
{
    if (!condition)
    {
        TMSystem.getLogFacility().error().write("Could not");
        return;    
    }
    
    doSomething();
    doMore();
}

public void goodExample2(Object x)
{
    if (x != null)
    {
        doSomething();
    }
}

public void badExample2(Object x)
{
    if (x == null)
    {
        return;    
    }
    
    doSomething();
}

public void goodExample3()
{
    if (condition)
    {
        boolean a = call();
        if (a)
        {
            doSomethingWith(a);
            doMore();
        }
        else
        {
            TMSystem.getLogFacility().error().write("Could not because of a");    
        }
    }
    else
    {
        TMSystem.getLogFacility().error().write("Could not");    
    }
}

public void acceptableExample3()
{
    if (!condition)
    {
        TMSystem.getLogFacility().error().write("Could not");
        return;    
    }
    
    boolean a = call();
    if (!a)
    {
        TMSystem.getLogFacility().error().write("Could not because of a");
        return;
    }
    
    doSomethingWith(a);
    doMore();
}
```
- Avoid `continue` statements in loops unless necessary. Examples:
```
public void goodExample4()
{
    for (...)
    {
        if (condition)
        {
            doSomething();
        }
    }
}

public void badExample4()
{
    for (...)
    {
        if (!condition)
        {
            continue;
        }
        
        doSomething();
    }
}
``` 
- If a method needs many exit points, consider refactoring it.
- Keep condition expressions simple and easy to understand.
- Break complicated conditions into helper methods or named booleans.
- Favor readable code over concise code.
- Avoid uncertainty in method names and behavior.
- Avoid combined “doThisAndThat” methods when separate steps would be clearer.
- Methods that compute or retrieve values should not have surprising side effects.
- Getters must not mutate state.
- Prefer pure functions and immutable objects where practical.
- Prefer `final` fields when feasible.
- Prefer `final` method parameters when that fits local conventions and does not harm readability.

## Class Design and Initialization

- Follow a consistent class structure. A typical order is:
  1. public static fields
  2. private static fields
  3. instance fields
  4. constructors
  5. public static methods
  6. public methods
  7. protected and private static methods
  8. protected and private methods
  9. inner classes
- Do not call non-final, non-private overridable methods from constructors.
- Keep constructors simple and limited to basic initialization.
- If initialization is complex and requires calling other methods, move that logic into a dedicated `initialize()` step.
- Design initialization so subclasses do not observe partially constructed state.
- Prefer immutable classes where practical and where performance is acceptable.
- Define `serialVersionUID` in all classes that implement `Serializable` and related serialization interfaces.

## Configuration and Externalization

- Never hardcode connection parameters such as URLs, IP addresses, or port numbers in source code.
- Even when these values appear static during initial development, they inevitably change across environments and client deployments; they belong in configuration, not in code.
- The same principle applies to hostnames, service endpoints, and any other environment-specific values.

## Exceptions and Error Handling

- Declare checked exceptions for error conditions that are part of normal operation, such as database or remote-service failures.
- Do not hide expected operational failures behind unchecked exceptions when the caller needs to handle them.
- When accessing Java objects by a string name, especially from configuration, fail with informative exceptions rather than allowing an eventual `NullPointerException`.
- Exception messages should clearly describe what lookup or operation failed.
- Avoid empty `catch` blocks.
- In the rare cases where an empty or intentionally suppressed `catch` block is justified, explain the reason in a clear comment.

## Logging and Diagnostics

- Use the appropriate log facility for the layer or subsystem.
  - Example: in TMS/TMSV-level classes, use `TMSystem.getLogFacility()`, not `GlobalSystem.getLogFacility()`.
- When adding temporary debug output, include the class name and, in larger classes, the method name.
- Prefer informative error and validation messages over vague failures.

## Java Style

- Use the established brace style consistently.
- Use four spaces for indentation; configure editors to replace tabs with spaces.
- Prefer imports over fully qualified class names in code, except when needed to resolve name conflicts.
- Prefer explicit imports to wildcard imports when no more than 5 classes from a package are imported
- Use empty lines between methods and logical blocks for readability.
- Prefer direct, readable comparisons and equality checks. For example, `if (varName == value)` in most cases is better than `if (value == varName)`

## XML Style

- XML element names should be capitalized, with internal words capitalized.
- XML attribute names should not start capitalized, but internal words should be capitalized.
- Use tab for indentation, do not use 4-space indentation.
- For empty XML elements, use self-closing form:

```xml
<DoThis/>
```

- Do not use expanded open/close tags for empty elements.
- After changing XML files, run the project beautification script before committing.

## Reuse and Utilities

- Before adding a new helper, check existing `*UtilityFunctions` and `*Utils` classes.
- Prefer established team utility methods when they already solve the problem correctly and comprehensively.

## GUI and Concurrency Notes

- Be cautious with deferred UI execution such as `invokeLater`.
- Use it only when there is a clear need and the reasoning is understood.
- When using unusual threading or event-queue behavior, leave a clear comment explaining why.

## Testing

- Place unit tests close to the code under test.
- For a class `ClassUnderTest.java`, prefer `ClassUnderTest_utest.java` in the corresponding test location.
- When several test classes need common setup, avoid duplication by extracting a shared base test or shared test utilities.
- For `*_utest.java` classes, apply team-required annotations such as:
  - `@SuppressWarnings("NewClassNamingConvention")`
  - `@FixMethodOrder(MethodSorters.NAME_ASCENDING)`
- Use JUnit 4 and EasyMock by default.
- Use Mockito only when JUnit 4 and EasyMock are insufficient.

## Nullability and Immutability

- Where applicable, annotate new classes and interfaces with `@ParametersAreNonnullByDefault` and `@ReturnValuesAreNonnullByDefault`.
- Prefer immutable objects and `final` fields where practical.

## Inheritance and Substitutability

- Do not violate the Liskov Substitution Principle.
- When overriding a superclass method, verify that the new behavior remains compatible with the superclass contract.
- Not calling `super` is not automatically wrong, but it is a signal to verify the inherited contract carefully.

## Copyright and File Headers

- For new source files, add the required copyright header in the approved format.
- Keep the header text in sync with the current company policy.

## Instructions for AI Coding Assistants

These rules are specifically intended for generative AI agents and code-generation workflows.

- Do not generate dead, unused, unreachable, or speculative code.
- Do not introduce new helpers, abstractions, or wrappers unless they are needed for the requested change.
- Do not leave placeholder implementations, fake TODO logic, or “future-proofing” scaffolding unless explicitly requested.
- Do not silently change behavior outside the requested scope.
- Reuse existing project utilities, naming patterns, package conventions, and surrounding code style.
- Keep edits minimal and localized when the request is narrow.
- When changing behavior, update related tests or add tests when appropriate.
- Do not duplicate existing logic that already exists elsewhere in the codebase.
- Remove imports, variables, methods, and branches that become unused because of your change.
- Do not invent APIs, config keys, resource paths, wiki references, or utility methods unless explicitly requested; use only what exists or clearly mark assumptions.
- Preserve comments, documentation markers, and required file headers unless the task explicitly changes them.
- Prefer explicit, readable code over compact but opaque code generation.
- Avoid broad refactors unless explicitly requested or required for correctness.
- When a rule conflicts with existing code in the immediate area, follow the established local pattern unless the task is to clean it up.

## Notes on Judgment and Exceptions

These guidelines are intended to improve consistency and maintainability, not to replace engineering judgment.

- If a rare exception is justified, keep it deliberate, minimal, and documented.
- if you feel that any of these rules are counterproductive in your case:
  - [human coder] bring up with the management to review and optimize the rule
  - [AI agent] report your concerns to your human user but still follow the rules
