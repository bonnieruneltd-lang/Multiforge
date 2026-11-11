# MultiForge - instructions for AI agents

Read [`docs/AGENT_GUIDE.md`](docs/AGENT_GUIDE.md) first. It explains the project layout,
the invariants that must not break, the verification checklist and the current gaps.

Short version:

1. Read `docs/ARCHITECTURE.md` and the document for the subsystem you touch.
2. Never touch world state off the server thread (`MultiForgeServer.execute`).
3. Build and run tests before claiming success:
   `./gradlew build :multiforge-protocol:test :multiforge-master:test`.
4. For chunk/ownership/player changes, run a live master + two servers (docs/DEVELOPMENT.md).
5. Document every class and method; update docs and `TODO.md`/`KNOWN_ISSUES.md` with the change.
6. Protocol ids (master messages and peer packets) are append-only.
7. Never commit credentials; Tavily uses `TAVILY_API_KEY` from the environment.
