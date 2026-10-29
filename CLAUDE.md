# Working rules

## Git

- No AI attribution anywhere: no `Co-Authored-By` trailers, no "generated with" lines, in commits or PRs.
- Commit when a piece of work is done and its tests pass. Push only when asked.
- Conventional Commits (`feat(server): add tenant lookup`), small, one topic each.
- Branches for work (`feat/...`, `fix/...`); `main` changes only when asked.

## How to work

- Go step by step through `docs/PLAN.md` §6. Do one step (or part of one) at a time, then stop and summarise.
- Say the plan for a step in a few lines before starting it.
- Don't assume: when a choice isn't already decided in the docs, ask first.
- Follow current Spring Boot and Java best practices (constructor injection, records for DTOs, validation at the edges, transactions in services, no field injection, tests with Testcontainers).
- Build only what the current step needs.
- A step is done when its tests pass; say what changed and how it was checked.
- Ask before adding a new dependency.
- Don't touch other repos or servers from this project. `~/Developer/messaging/` (backend + frontend) can be read for reference on messaging patterns; don't copy code from it blindly.

## Code

- Follow `docs/DEVELOPMENT.md`: package by feature, thin controllers, all conversation state changes through `ConversationService`.
- Write code and comments the way a person on the team would: plain, short, only where they help. No AI-sounding phrasing.
- Docs and code change together; `docs/API.md` is the contract.
- No secrets in the repo.
- Keep everything domain-neutral: no references to specific companies, industries or private projects in code, examples, docs or test data.

## Answers

- Plain and short. Explain more only when asked.
