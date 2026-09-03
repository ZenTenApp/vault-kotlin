# Repository instructions for AI assistants

## Code comments

- Add concise explanatory comments for non-obvious code so a beginner can understand what the logic does and why. Cover intent, constraints, invariants, and non-trivial decisions without restating self-evident code. Keep comments accurate and update or remove them when the code changes.

## Documentation maintenance

Whenever a change modifies implemented behavior, user-visible flows, security properties, configuration, architecture, dependencies, tests that establish a contract, or known limitations, update the relevant documentation in the same change.

- Update the most specific document under `docs/features/`, `docs/technical/`, or `docs/stakeholder/`.
- Add a new document and link it from the appropriate documentation index when no existing document fits the change.
- Keep source-location references, stated behavior, safeguards, limitations, and implementation status accurate.
- If no documentation update is necessary, explicitly state why in the final response.
