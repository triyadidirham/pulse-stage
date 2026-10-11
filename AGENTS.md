# Repository conventions

## Commit messages

Use Conventional Commits for every new commit, as requested by the project owner.

Format: `type(scope): short description`. Scope is optional. Use lowercase types,
an imperative English description, and no trailing period.

- `feat`: new user-facing functionality
- `fix`: bug fixes
- `refactor`: changes to code structure without changing behavior
- `docs`: documentation and repository instructions
- `test`: automated tests
- `build`: build configuration and dependencies
- `ci`: continuous integration workflows
- `perf`: performance improvements
- `style`: formatting without behavior changes
- `chore`: other maintenance

Prefer scopes matching the affected area: `server`, `web`, `wear`, `protocol`, or
`infra`. Use `!` and a `BREAKING CHANGE:` footer for breaking changes, explaining
the required migration. Never rewrite already published commits just to change
their message format unless the user explicitly requests it.

Examples:

```text
feat(web): add overlay color selection
fix(wear): reconnect after a network interruption
refactor(server): separate database access from HTTP handling
docs(infra): explain deployment with Docker Compose
```

Specification: https://www.conventionalcommits.org/en/v1.0.0/
