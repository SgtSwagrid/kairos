# CLAUDE.md

This file provides guidance to [Claude Code](https://claude.com/product/claude-code) when working with code in this repository.
It is not intended for human eyes.

### Maintenance

You (robot or human) have standing permission to update this file without asking.
Add important patterns, gotchas, or context that would help future sessions.
Keep it concise and actionable.

## Project overview

Kairos: a scheduling tool that picks the best (venue, date) option for an event by
eliciting as little availability information from participants as possible. See
[README.md](README.md) for the problem statement, the measured behaviour, and the
formal background.

### Where things live

- `common` holds the whole domain model *and* the whole solver, cross-compiled to JVM
  and JS. Put anything with real logic here; the server and client should be thin.
- The solver pipeline is Belief -> Ensemble -> Forecast/Verdict -> Elicitation, with
  `Analysis.of` as the single entry point.
- `server` serves both the JSON API (`/api/polls/...`) and the three HTML pages, which
  all use the same template and differ only in which view name is injected.
- `client` reads the poll and participant identifiers out of `window.location` via
  `views/Route.scala`, since the template does not pass them.

### Things that will bite you

- The style guide says `@return` takes an indefinite article. That holds for
  descriptions of something newly produced ("A forecast for that slot") but not
  for ones naming a specific thing, a plural or a possessive ("The chosen slot's
  share", "The indices of the worlds to use"). Both forms are in use on purpose;
  do not rewrite one into the other wholesale, which produces "A polls it held".

- **Do not score questions by expected improvement in the choice.** It is exactly zero
  whenever a single answer would not flip which option leads, which is most of the
  time, so rounds stall after a few questions. `Elicitation` uses mutual information
  with the identity of the winning option instead. The full reasoning is in that
  file's Scaladoc; read it before changing the objective.
- **Question worth is in bits and is not monotonically decreasing** along a round,
  because mutual information is not submodular within a participant. Do not "fix" this.
- **The value of information can rise between rounds.** Also not a bug.
- **`Verdict` splits tied worlds between the slots that tied; `Elicitation.victors`
  must not.** The first reports a probability, where splitting is correct. The
  second defines the target that questions are scored against predicting, so it
  has to be a function of the world alone — sharing ties out makes part of the
  target unpredictable in principle and sets the search chasing noise. Doing it
  there took the worked example from 93% of the best available value to 39%, and
  passed every test but the wide-field one in `SimulationSuite`.
- **Test solver changes on a wide field.** With around ten slots a solver choosing
  nearly at random still lands near the best by luck. The regression above slipped
  past seventy tests because every fixture was too small.
- **`Poll.bounds`, not `Poll.objective`.** The solver reads the bounded objective,
  so a hand-edited file cannot carry a setting that breaks it.
- `Service` deliberately provides only `api`. Documentation and metrics are assembled
  once across all services in `Assembly`; a service that provided its own would
  collide with the next one (Prometheus refuses duplicate metric registration, and
  only the first `/docs` would be reachable).
- `Day` is a hand-rolled epoch-day type because `java.time` has no Scala.js build.
  `DaySuite` checks it against known dates over a 160-year span.
- Tapir schemas are written out explicitly in `api/Schemas.scala` rather than derived
  with `sttp.tapir.generic.auto`, which fails opaquely on the opaque types and enums.
- The forked dev JVM's working directory is `server/`, so the data file path is set
  explicitly via the `data.file` system property in `Subprojects.scala`.

### CI is stricter than `sbt build`

`build-integrity.yml` compiles with `-Werror`, which the local build does not, so a warning that
looks harmless here fails there. Before pushing, check with:

```bash
echo 'ThisBuild / scalacOptions ++= Seq("-Werror", "-deprecation")' > werror.sbt
sbt build
rm werror.sbt
```

Laminar's `cls.toggle("x")` is deprecated in favour of `cls("x")`, which now does the same thing;
that one has already caught us once.

### Running it

- `sbt dev` then [localhost:8080](http://localhost:8080); click "Create a worked example".
- `sbt test` runs everything CI runs. **The solver's tests run on Scala.js as well
  as the JVM**, and Scala.js is several times slower, so a suite that is
  comfortable on the JVM can exceed munit's 30-second default there and fail the
  build. Check both before pushing; `sbt "common/testOnly -- *"` alone will not
  tell you. Note also that `-- *` is a framework option rather than a filter, and
  Scala.js rejects it: use `sbt "testOnly *"` for a full run.
- `SimulationSuite` is skipped on Scala.js through `munitIgnore`, being minutes of
  arithmetic that re-checks what the JVM run has already established.

### Deployment

See [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md). `.github/workflows/deploy.yml` builds the `Dockerfile`
after CI passes on `main`, pushes it to GHCR and runs `deploy/compose.yml` (the app behind Caddy) on
the server named by the `DEPLOY_HOST` variable over SSH; pull requests touching packaging only build
and try the image. The fat JAR does not contain its assets: the image copies the server's
`resource_managed/main/assets` beside it and passes `-Dassets.dir`. Serve the site from a host of
its own, never a sub-path, as every URL is absolute. Anything the server reads from its environment
is supplied through the `APP_ENV` secret, never baked into the image.

## Instructions

### Compilation and Diagnostics

- When the user asks for help with a compilation or type error, start by running `sbt compile` to see the error for yourself.
  If there are many errors, making it unclear which one the user is referring to, ask them to clarify, and then focus only on that issue.
- IntelliJ MCP integration is active. When a request seems to implicitly refer to something the user is looking at, always check
  `mcp__ide__getDiagnostics` first to see which file(s) are open and get associated diagnostics (errors, warnings, and info hints with line numbers).

#### Testing

- After making code changes, always run `sbt compile` to verify that issues are fixed and no new ones are introduced.
- Repeatedly retry upon failure until the build succeeds. If you are unsure how to fix an issue, ask for help or refer to existing code for examples.
- Before trying to fix an error, make sure you first understand it fully.
- You should never report that a feature is complete without testing it first.

### Code Style

- You must read the [Code Style Guidelines](docs/STYLE_GUIDE.md).

### Pull Requests

When asked to publish the code changes, your task is to open one or more pull requests (PRs) to merge the changes into `main` on GitHub:

- Use `git` to check what has changed as compared to the `main` branch on `origin`.
- If the changes are thematically linked, they can be published as a single PR.
- Otherwise, you'll need to divide the changes into multiple PRs using your own judgement.
- Each PR should have a singular focus, shouldn't break anything, and should be able to be merged independently.
- Ensure that all code is staged, committed and pushed. Ensure no new files are left uncommitted, and no debug code is left in the codebase.
- When creating a PR, ensure that the title and description are clear, informative, and comprehensive.
- All feature/bugfix/etc branch names should be formatted as "feature_<short description>" or "fix_<short description>" or similar.
- All PR titles should be formatted as "[<scope>] <Short summary>", e.g. "[renderer] Fixed colour inversion bug."
- Use the `gh` CLI to do the above.
