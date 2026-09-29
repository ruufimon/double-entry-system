# Scala Coding Style

This project favors explicit effects, typed errors, and clear boundaries between application logic and infrastructure.

## Naming and Structure

- Use descriptive names for values, functions, and types.
- Use `PascalCase` for types and enums, and `camelCase` for methods and values.
- Group code by responsibility: domain types, ports, infrastructure adapters, application services, and composition.
- Keep functions small and make their inputs and outputs explicit.
- Prefer immutable `val`s. Use `var` only when mutation is necessary.

## Types and Errors

- Represent expected failures with a domain-specific error type.
- Return `Either[DomainError, A]` when an operation can fail in a known, expected way.
- Avoid using `Option` when callers need to know why an operation failed.
- Keep technical exception details inside infrastructure adapters; translate them into domain errors at the boundary.
- Provide a useful fallback message when an exception has no message.

Example:

```scala
enum FileError derives CanEqual:
  case NotFound(path: Path)
  case AccessDenied(path: Path)
  case IoFailure(path: Path, message: String)