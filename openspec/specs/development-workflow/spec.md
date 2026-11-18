# Backend change workflow

## Purpose

Keep an agreed backend change, its executable contracts and its verification aligned from Jira intake to an authorized merge.

## Requirements

### Requirement: Start from a verified task and baseline

The developer SHALL verify the current Jira status, assignee, dependencies, related PRs and current develop before starting a separate task branch.

#### Scenario: An available task is started

- **WHEN** a user authorizes a backend task and its dependencies are complete
- **THEN** the developer creates a separate task branch from current develop and records the intended behavior and acceptance scenarios before implementation

#### Scenario: Another developer owns the task

- **WHEN** Jira assignment or an active PR shows that the task is already being developed
- **THEN** the developer preserves that work and selects another authorized available task or coordinates explicitly

### Requirement: Business decisions remain explicit

The developer SHALL create a separate linked Jira task for an unresolved business question and SHALL describe the decision, alternatives and affected acceptance criteria.

#### Scenario: A business question blocks a change

- **WHEN** the implemented contract and accepted requirements do not determine an observable product behavior
- **THEN** the developer records a linked decision task, identifies the blocked work and continues independent work without inventing a decision

### Requirement: Verify the final implementation

The developer SHALL choose meaningful checks from the harness, verify actual fresh test reports and repeat affected E2E after final review.

#### Scenario: A final head changes after review

- **WHEN** another commit changes behavior, shared contracts or a verification runner
- **THEN** the developer repeats affected checks and E2E on the resulting source and does not present evidence from the prior head as current

#### Scenario: A required external capability is unavailable

- **WHEN** a scenario cannot run because an external provider, credential or fixture capability is unavailable
- **THEN** the developer reports the scenario as a gap with its scope and reason rather than as passed

### Requirement: Merge stays within user authorization

The developer SHALL check the current PR head, CI, review findings and compatibility before an authorized merge into develop.

#### Scenario: A change is ready for merge

- **WHEN** the user has authorized merge and the applicable checks and review findings have been addressed
- **THEN** the developer merges the verified head, confirms its presence in develop and distinguishes merge from actual deployment health

### Requirement: OpenSpec complements authoritative contracts

OpenSpec SHALL describe the intended and accepted behavior while REST, protobuf and service-owned migrations remain authoritative executable contracts.

#### Scenario: A shared contract changes

- **WHEN** an implementation changes REST, RPC, events or an owned database schema
- **THEN** the developer updates the authoritative sources and their consumers, keeps OpenSpec aligned and preserves applied migration history
