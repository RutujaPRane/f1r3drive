# F1r3Drive

F1r3Drive is a **FUSE-based filesystem that stores data on the F1r3fly blockchain**. It is written in Java (17) and uses [JNR-FUSE](https://github.com/SerCeMan/jnr-fuse) to expose a mountable drive, connecting to a running F1r3fly node over gRPC. Files and directories you create in the mounted drive are chunked and deployed to the blockchain in the background; wallet directories, REV token transfers, and (optionally) transparent AES encryption are surfaced as ordinary filesystem operations.

The CLI entry point is `io.f1r3fly.f1r3drive.app.F1r3DriveCli` (`mainClass` in the shaded JAR).

## Build & Run

This project builds with Gradle (wrapper committed) and targets Java 17. The dev environment is provided via Nix + direnv (`flake.nix`); run `direnv allow` once to enter it. `protoc` is required for the gRPC/protobuf code generation.

```bash
# Build the fat JAR (skip tests) -> build/libs/f1r3drive-app.jar
./gradlew shadowJar -x test

# Run the app (mounts a FUSE drive)
java -jar build/libs/f1r3drive-app.jar <mount-point> \
  --key-file <aes-key-file> \
  --host localhost --port 40412 \
  --observer-host localhost --observer-port 40452 \
  --address <rev-address> --private-key <key>

# Or via Gradle
./gradlew run --args="<mount-point> --key-file <key> ..."
```

Running F1r3Drive requires a reachable F1r3fly shard (validator + observer gRPC). The easiest path is `shardctl` from the `system-integration` repo (see README). FUSE libraries must be installed on the host (macFUSE on macOS; libfuse on Linux — see `INSTALLATION.md`).

Key CLI flags: `--key-file/-k` (AES key, generated if absent), `--host/-H` + `--port/-P` (validator gRPC), `--observer-host/-O` + `--observer-port` (observer gRPC), `--address/-a` + `--private-key/-K` (wallet to auto-unlock), `--manual-propose` (propose + await finalization after each deploy; default relies on the shard's autopropose/heartbeat), `--debug/-d` (verbose FUSE logging). Full reference: `docs/configuration.md`.

## Testing

```bash
./gradlew test                      # Unit tests (JUnit 5)
./gradlew e2eTest --rerun-tasks     # End-to-end tests — REQUIRE a running shard
```

- Unit tests live in `src/test/java`. E2E tests live in a separate `e2e` source set (`src/e2e/java`) and are **not** part of the normal build — they use Testcontainers to spin up a local shard and exercise the mounted filesystem.
- `docs/features.md` maps each user-facing feature to the e2e test that validates it, and lists `@Disabled` tests for planned features (`.rho`/`.metta` deploy-on-rename, transparent `.encrypted` encryption, 512 MB large-file support).

## Architecture

Packages under `src/main/java/io/f1r3fly/f1r3drive/`:

- **`app/`** — CLI (`F1r3DriveCli`, picocli) and the FUSE binding. `app/linux/fuse/F1r3DriveFuse` + `FuseAdapter` translate JNR-FUSE callbacks into `FileSystem` operations and own mount/unmount lifecycle (including the double-Ctrl+C hard-stop and shutdown hook).
- **`filesystem/`** — the core abstraction. `FileSystem` is the interface; `InMemoryFileSystem` is the implementation. The path hierarchy is modeled as typed nodes:
  - `filesystem/common/` — base `Path`/`Directory`/`File`/`ReadOnlyDirectory`.
  - `filesystem/local/` — local-only nodes: `RootDirectory`, `LockedWalletDirectory`, `TokenDirectory`/`TokenFile`. A `LOCKED-REMOTE-REV-*` folder appears empty until unlocked with a valid REV address + private key.
  - `filesystem/deployable/` — nodes whose state is pushed to the blockchain: `BlockchainFile`/`BlockchainDirectory`, `FetchedFile`/`FetchedDirectory`, `UnlockedWalletDirectory`.
  - `filesystem/bridge/` — JNR struct adapters (`FSFileStat`, `FSFillDir`, `FSPointer`, etc.) decoupling FUSE structs from filesystem logic.
- **`blockchain/`** — F1r3fly client layer. `client/F1r3flyBlockchainClient` (gRPC), `client/DeployDispatcher` (bulk background deploys), `rholang/RholangExpressionConstructor` (builds Rholang terms for storage), `wallet/` (REV address/key validation). See `docs/rholang_data_storage.md` for the on-chain data model.
- **`background/state/`** — asynchronous event pipeline. A `BlockingEventQueue` feeds `EventProcessor`s via `StateChangeEventsManager`, so writes are acknowledged locally and synced to the chain in the background (see `docs/system_data_flow.md`).
- **`encryption/`** — `AESCipher` (singleton, initialized from `--key-file`) for transparent encryption.
- **`finderextensions/`** — `FinderSyncExtensionServiceServer`, a local gRPC server (`localhost:54000`) that the optional [macOS Finder Extension](https://github.com/F1R3FLY-io/f1r3drive-extension) talks to for badges, context menus, and auto-unlock.
- **`errors/`** — typed filesystem exceptions (`PathNotFound`, `OperationNotPermitted`, `FileAlreadyExists`, etc.).

**Data flow (write):** FUSE write → `InMemoryFileSystem` updates the in-memory tree and stages data on local disk → acknowledges immediately → `DeployDispatcher` bulk-deploys chunks + directory updates to the shard in the background. Reads pull chunks back through the cache. Tokens are modeled as `.token` files under a per-wallet `.token` directory; moving a `.token` file into another wallet folder triggers an on-chain REV transfer.

## Protobuf / gRPC

Proto sources live in `src/main/proto` plus vendored copies in `src/main/protobuf_external`, `src/main/protobuf_models`, and `scalapb`. The `com.google.protobuf` Gradle plugin generates Java + gRPC stubs into `build/generated/source/proto/main/{java,grpc}` (wired into the main source set). Regenerate by rebuilding; do not hand-edit generated files.

## Conventions

- **Java 17**, Gradle `java-library`. The published/runnable artifact is the **shaded** JAR (`shadowJar`, `archiveFileName = f1r3drive-app.jar`); `org.objectweb.asm` is relocated to avoid conflicts.
- Logging via SLF4J + Logback.
- App version is declared in **two** places that must stay in sync: `gradle.properties` and the `@Command(version = ...)` annotation in `F1r3DriveCli.java`.
- Releases use the `net.researchgate.release` plugin; commit messages for releases are prefixed `[f1r3Drive release]`.
- Branch model: work on `dev`; `main` is the PR target.
- **Git hooks** (opt-in, install with `./scripts/setup-hooks.sh`): `.githooks/pre-commit` runs `./gradlew spotlessCheck` (Spotless + google-java-format, `ratchetFrom 'HEAD'` so only changed files are checked — fix with `./gradlew spotlessApply`); `.githooks/pre-push` runs `./gradlew test` + `shadowJar`. e2e tests are excluded from hooks (need a live shard).

### Key Principles

1. **Stigmergic Collaboration**: Coordinate with other agents through shared `.md` files
2. **Document-First**: Create design docs and specifications BEFORE implementation
3. **Signal vs. Slop**: Maximize code that solves problems; avoid over-engineering
4. **Acceptance Criteria**: Define measurable success criteria in task definitions

### Standard Document Structure

| Document | Purpose | Location |
|----------|---------|----------|
| User Stories | Business needs and acceptance criteria | `docs/UserStories.md` |
| Tasks/Epics | Implementation tracking | `docs/ToDos.md` |
| Completed Work | Historical reference | `docs/CompletedTasks.md` |
| Backlog | Deferred items | `docs/Backlog.md` |
| Work Logs | Session progress | `docs/work-logs/*.md` |
| Discoveries | Shared findings | `docs/discoveries/*.md` |

### Before Starting Work

1. **Read `docs/ToDos.md`** to check task status and claims
2. **Check `docs/work-logs/`** for existing progress on related tasks
3. **Review `docs/discoveries/`** for relevant context from other agents

### When Claiming a Task

Update the task in `docs/ToDos.md`:

```yaml
---
id: TASK-001
status: in_progress          # Changed from 'pending'
claimed_by: claude-session-a1b2c3  # See Implementer Identification format
claimed_at: 2025-01-15T10:00:00Z
# Other valid claimed_by formats:
#   human-jeff@example.com        # Human (git config --get user.email)
#   design-sprint/researcher      # Agent team member ({team}/{name})
---
```

### During Work

1. **Create work log** at `docs/work-logs/task-{id}-{timestamp}.md`
2. **Document discoveries** in `docs/discoveries/` for other agents
3. **Update blockers** if you encounter dependencies

### Before Pausing/Completing

Update your work log with handoff notes:

```yaml
---
handoff_status: ready | paused | blocked
next_steps:
  - What remains to be done
---
```

## AI Artifact Generation Guidelines

**Core strategy:** Default to **Markdown + Mermaid** as the source of truth for all generated artifacts. Use **HTML** only when high engagement or advanced interactivity is required.

**Preferred formats:**

| Format | Use for | Notes |
|--------|---------|-------|
| **Markdown + Mermaid** | Primary. Diagrams (flowcharts, sequences, architecture, timelines, Gantt, ERDs), structured documents, plans, specs | Relative links (`./images/`, `./docs/`) and GitHub/GitLab raw URLs for local/cloud asset referencing |
| **HTML (CSS/JS + embedded Mermaid)** | Secondary. Interactive dashboards, prototypes, dynamic reviews, stakeholder deliverables | When visual polish and engagement are critical (tabs, sliders, clickable elements) |

**Hybrid rule:** Always produce Markdown as the canonical, Git-friendly version first; generate a self-contained HTML export on request.

**Key principles:**

- Prioritize human readability, editability, and Git compatibility (clean diffs, relative paths, native rendering on GitHub/GitLab).
- Maximize information density while avoiding text walls — convert complex information into Mermaid diagrams.
- Support seamless referencing of local files and cloud artifacts (images, other docs, raw Git content).
- Favor Markdown for internal/agent use and long-term storage (token efficiency).
- Use HTML when delivering to stakeholders or for living documents (engagement).

**Output guidance:** When creating artifacts, ask whether HTML interactivity is needed. Default to clean Markdown with embedded Mermaid unless specified.

## UI Test Assertions

React UI tests should prove user-observable DOM structure and state, not incidental copy or formatted sample values. Treat exact text/value assertions as a last resort unless the behavior under test is specifically copy, formatting, or content transformation.

- **Preferred:** Accessibility-first queries for DOM elements and states: `getByRole`, `getByLabelText`, `aria-label`, `aria-labelledby`, `aria-selected`, `aria-expanded`, `aria-disabled`, focus state, and ARIA relationships.
- **Preferred:** Assert semantic regions/components are present and wired correctly (tabs, tabpanels, dialogs, forms, buttons, lists), then assert behavior through state changes or callback/data-source calls.
- **Acceptable:** `data-testid` attributes when no accessible handle exists, the element is purely presentational, or the test needs to identify a stable component boundary rather than text content.
- **Acceptable:** HTML `id` attributes for form elements and ARIA relationships.
- **Avoid:** `getByText`/`queryByText` for literal strings that are merely display copy, repeated metrics, formatted numbers, or mock-data values.
- **Avoid:** `getAllByText(...).length` as a substitute for a meaningful DOM assertion; it couples tests to duplicated visual text rather than behavior.
- **Avoid:** CSS class selectors that may change with styling updates.

Examples:

```tsx
// GOOD: accessibility-first DOM element + state
expect(screen.getByRole("button", { name: "Refresh agents" })).toBeEnabled();

// ACCEPTABLE: fallback when no accessible role/name fits
expect(screen.getByTestId("cost-optimization-panel")).toBeInTheDocument();

// GOOD for data-flow behavior: verify source + rendered component boundary,
// not a duplicated metric string like "7.5%".
expect(fetchQualityPipeline).toHaveBeenCalledTimes(1);
expect(screen.getByRole("tablist", { name: "Quality Pipeline tabs" })).toBeInTheDocument();
expect(screen.getByRole("tab", { name: /overview/i })).toHaveAttribute("aria-selected", "true");

// BAD: brittle copy assertion
expect(screen.getByText("Priority set by BountyForge routing")).toBeInTheDocument();

// BAD: brittle mock-value / duplicated visual text assertion
expect(screen.getByText("7.5%")).toBeInTheDocument();
expect(screen.getAllByText("7.5%").length).toBeGreaterThan(0);
```

**When exact text is appropriate:** Use exact text assertions only when the acceptance criteria is about user-facing copy, accessibility name computation, validation messages, formatting rules, or transformed content. Prefer scoping with `within(...)` to a semantic container so the assertion remains tied to the behavior under test.

### Configuration File Conventions

When creating or modifying configuration files, follow these conventions to respect existing project preferences:

**JSON Format Preference Order:**

1. **Check for existing files first**: Before creating any `.json` file, check if `.jsonc` or `.json5` variants exist
2. **Prefer existing format**: If `config.jsonc` or `config.json5` exists, use that format instead of creating `config.json`
3. **Default to JSONC**: When creating new config files, prefer `.jsonc` (JSON with Comments) for better maintainability

**Why This Matters:**
- Projects may have established preferences for comment-supporting JSON formats
- Creating duplicate configs (e.g., both `biome.json` and `biome.jsonc`) causes confusion
- JSONC allows inline documentation which improves maintainability

**Examples:**

| If exists... | Don't create... | Instead... |
|--------------|-----------------|------------|
| `biome.jsonc` | `biome.json` | Edit the existing `biome.jsonc` |
| `tsconfig.json5` | `tsconfig.json` | Edit the existing `tsconfig.json5` |
| `eslint.config.jsonc` | `eslint.config.json` | Edit the existing file |
| Nothing | - | Create new file as `.jsonc` when comments are useful |

**File Discovery Pattern:**

Before creating any config file, check for variants:
```bash
# Check for config variants (example for biome)
ls biome.json biome.jsonc biome.json5 2>/dev/null
```

This applies to all slash commands and scripts that create configuration files.

#### Git Operations
- `/quick-commit` - Stage and commit changes (required in safe mode)
- `/recursive-push` - Push across repositories

#### Task Management
- `/nextTask` - Find and select next task to work on
- `/implement` - Begin implementation of a task
- `/epic-review` - Preview and summarize epics
- `/epic-hygiene` - Archive completed epics

#### Workspace Sync
- `/harmonize` - Sync workspace policies into this repo
- `/multi-repo-sync` - Workspace-wide sync orchestration

- `/work-tasks` - Work through tasks in `docs/ToDos.md` autonomously
- `/story` - Create and link user stories in `docs/UserStories.md`

## PII Guidelines for Contributors

**CRITICAL - Before submitting any contribution:**

Contributors MUST ensure their code, commits, and documentation do NOT contain PII:

**Check before committing:**
- [ ] No absolute file paths with usernames in code or documentation
- [ ] No personal email addresses in code (use generic examples like `user@example.com`)
- [ ] No real user data in tests or examples (use synthetic/fake data only)
- [ ] No PII in log statements (sanitize or use user IDs instead)
- [ ] No PII in error messages or stack traces
- [ ] No PII in code comments or documentation
- [ ] No credentials, tokens, or secrets in code (use environment variables)
- [ ] No IP addresses, MAC addresses, or device identifiers in examples

**If you accidentally committed PII:**
1. **DO NOT** push to remote repository
2. Use `git reset` to remove the commit
3. If already pushed, contact maintainers immediately
4. Repository history may need to be rewritten to remove PII

**Use these instead:**
- File paths: Use relative paths or generic placeholders (`[WORKSPACE_ROOT]/project/`)
- Email addresses: Use `user@example.com`, `admin@example.com`
- Names: Use `John Doe`, `Jane Smith`, `User123`
- Phone numbers: Use `+1-555-0100` (officially reserved for examples)
- IP addresses: Use reserved ranges (`192.0.2.1`, `198.51.100.1`, `203.0.113.1`)
- Dates: Use recent but generic dates, not specific personal dates

**For test data:**
- Use test data generators that create realistic but fake data
- Use well-known test fixtures (e.g., `test@example.com`)
- Never use production or real user data in development/testing

## grepai - Semantic Code Search

`grepai` is an optional, MIT-licensed semantic-search tool. If it is installed
and indexed locally for this repo, prefer it for intent-based code exploration;
if it is unavailable, fall back to your harness's native search and file-reading
tools. It is recommended but never required. Nothing here is harness-specific -
substitute your tool's equivalents for the generic actions described below.
Setup (with the privacy-first local Ollama embedder as default) lives in the
CLI Setup guide's "Optional: Semantic Code Search (grepai)" section.

# important-instruction-reminders
Do what has been asked; nothing more, nothing less.
NEVER create files unless they're absolutely necessary for achieving your goal.
ALWAYS prefer editing an existing file to creating a new one.
NEVER proactively create documentation files (*.md) or README files. Only create documentation files if explicitly requested by the User.
Before making any code changes, first state: (1) which files you plan to modify, (2) what approach you'll take, (3) any assumptions you're making. Wait for my confirmation before proceeding. For simple single-file edits, a one-line summary is sufficient.
