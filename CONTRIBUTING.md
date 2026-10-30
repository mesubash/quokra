# Contributing

Thanks for helping. Small, focused changes are the easiest to review.

## Before you start

- For a bug, open an issue with the steps to reproduce it (or go straight to a pull request if it's small).
- For a feature, open an issue first, so we can agree on it before you spend time on code. The
  [plan](docs/PLAN.md) says what's next.
- Security problems: see [SECURITY.md](SECURITY.md), not a public issue.

## Working on it

1. Set up and run it locally: [Development](docs/DEVELOPMENT.md).
2. Branch from `develop` (`feat/…`, `fix/…`, `docs/…`); pull requests go to `develop`, never `main`.
3. Follow the conventions in [Development §4](docs/DEVELOPMENT.md#4-conventions): package by feature, thin
   controllers, conversation changes through `ConversationService`, plain names and comments.
4. Add or update tests, and run them: `cd server && ./mvnw verify`, `cd web && pnpm test && pnpm build`.
   CI runs the same on your pull request.
5. Update the docs that your change affects; [API.md](docs/API.md) is the contract.
6. Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/)
   (`fix(web): keep the reply box focused after sending`).

Examples, test data and docs stay generic: no real companies, people or private projects.

## License

By contributing, you agree that your contribution is licensed under the [AGPL-3.0](LICENSE), like the
rest of Quokra.
