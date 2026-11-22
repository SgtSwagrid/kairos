<div align="center">

  <h1>🏔️ Kairos</h1>
  <p>Choose when and where to hold something, by asking the people invited as little as possible.</p>

  <span>
    <a href="https://github.com/SgtSwagrid/kairos/actions/workflows/build-integrity.yml"><img src="https://github.com/SgtSwagrid/kairos/actions/workflows/build-integrity.yml/badge.svg" alt="Build status" /></a>
  </span>

</div>

## 🎯 The problem

You have `m` candidate options — venues, each free at particular times, each holding
a limited number of people at some cost — and `n` people you would like to be there.
You know everything about the venues and nothing about the people.

You could ask everyone to mark their availability across a year. They will not do it,
and if they do, the answers will be wrong. You could name a date and ask whether it
works, but naming a date is not free: people start holding it, booking flights around
it, and taking offence when it moves.

So: **which few questions, put to which few people, would most improve the choice?**

Kairos answers that, one round at a time, and tells you when to stop asking and book.

## 🧭 How it works

1. **Enumerate the options.** Every placement of the event that a venue can actually
   host. This is the step that makes the rest tractable: typically a few dozen
   candidates rather than 365 days.
2. **Ask a little.** Questions are chosen to tell us as much as possible about *which
   option is best*, per unit of what they cost to ask. Broad questions ("could you
   travel somewhere in June?") come before named dates, because naming a date is dear.
3. **Model the rest.** Answers become a probability that each person attends each
   option. Thousands of simulated turnouts give the chance that each option is in fact
   the best one, and the risk of overrunning a venue.
4. **Stop when asking stops paying.** Kairos reports what having everybody's
   answers would be worth, in guests. Once that falls below the cost of another
   round, book. Note the wording: the part of the uncertainty that comes from
   somebody who *can* attend not turning up is measured separately and excluded,
   because no question resolves it.

### What it does in practice

Measured, not asserted. `SimulationSuite` hides a guest list from the solver, runs
the rounds, and checks the choice against the truth — across five different hidden
guest lists, because a single one flatters or damns it by luck.

On two venues over a summer, 11 options and 40 guests, five rounds of 30 questions
(about 1.5 questions per guest per round):

| Guest list | Share of the best available | True rank |
|---|---:|---:|
| 1 | 100% | #1 |
| 2 | 92% | #2 |
| 3 | 100% | #1 |
| 4 | 100% | #1 |
| 5 | 82% | #4 |
| **mean** | **95%** | |

Four rounds average 85%. On a wider field — 18 options, 32 guests — four rounds
find the best option outright.

Note the shape of it: the early rounds buy broad questions and settle the month,
and only then does it start naming dates. Setting the cost of naming a date to zero
makes it *worse*, not better: it wanders between months and ends several places
down. Screening first is not merely polite; it is the more efficient use of a round.

The value of information is also not monotone — learning that many guests are free
in July widens the gap between the options, so what it would cost to choose wrongly
can grow for a round before it shrinks.

## 🔬 What this is, formally

The problem sits at the intersection of several well-studied ones, and the
implementation borrows from each:

| Aspect | Problem class |
|---|---|
| Choosing questions to identify the best *decision*, not to learn everyone's diary | Decision-region determination; Bayesian experimental design |
| Broad questions whose negative answers rule out many options at once | Pooled group testing |
| Graded answers over candidate dates, elicited incrementally | Incremental preference elicitation with minimax regret |
| Knowing when to stop | Expected value of perfect information |
| Attendance against a venue's capacity | Poisson-binomial tail; stochastic knapsack |
| Choosing a whole round at once, in few rounds | Batched/low-adaptivity observation selection |

Two implementation notes worth reading if you intend to change the solver, both
recorded at length in [`Elicitation.scala`](common/src/main/scala/solve/Elicitation.scala):

- **Questions are scored by mutual information, not by expected improvement in the
  choice.** Scoring by improvement was tried and fails badly: one person's answer
  seldom overturns which option leads, and where it does not, the improvement is not
  small but *exactly zero*. Among thirty guests most questions then score nothing,
  the search cannot rank them, and rounds stop a few questions in.
- **Mutual information is not submodular within a participant.** Answers can be
  complementary — "could you travel in May?" and "could you travel in June?" are worth
  more together than apart — so the second question to someone may score higher than
  the first, and the greedy selection carries no approximation guarantee. It is still
  the standard and sensible choice; the breadth of a round is governed by the
  questions-per-guest limit rather than by the objective's shape.

## ⚖️ Settings that matter

Set on each poll, and adjustable from the dashboard:

- **Cost of naming a date** (default `3`) — how much dearer it is to ask about a named
  date than about a whole period. This is the setting that decides whether dates get
  named early. At `1` they always will.
- **Chance any date suits** (default `0.5`) — what to assume before anyone answers.
  Lower it when attending is a real imposition, as for long-haul travel; the worked
  example uses `0.25`.
- **Penalty per guest over capacity** (default `3`) — should exceed `1`, since turning
  away a guest who has accepted costs more than never inviting them.
- **Guests worth one unit of cost** (default `0`) — leave at zero to ignore price and
  choose purely on attendance.

## 🚧 Requirements

- **[Scala 3](https://www.scala-lang.org/)** — fetched by sbt; installing it separately is optional.
- **[sbt 2.1](https://www.scala-sbt.org/)** — the build tool.
- **[JDK 21](https://www.oracle.com/java/technologies/downloads/)** — the runtime.
- **[Git](https://git-scm.com/)** — for version control.

## ⬇️ Installation

```bash
git clone https://github.com/SgtSwagrid/kairos.git
cd kairos
```

## 💻 Local development

### Run a local development server
```bash
sbt dev
```
- Hot-reload is enabled: the server and client restart when code changes.
- Available at [localhost:8080](http://localhost:8080). Click "Create a worked example".
- API documentation at [localhost:8080/docs](http://localhost:8080/docs).

### Compile all subprojects
```bash
sbt build
```

### Run the tests
```bash
sbt test
```
- Runs the solver's tests on both the JVM and Scala.js, and the server's on the
  JVM, which is what CI does.
- The solver's suites include the end-to-end simulation described above; the
  server's cover the store, including that concurrent answers neither go missing
  nor corrupt the file.
- Under sbt 2 this reruns only what has changed. Use `sbt "testOnly *"` to run
  everything regardless.
- The simulations are skipped on Scala.js, where they take minutes to re-check
  what the JVM run has already shown.

### Format all code according to style rules
```bash
sbt lint
```

## ⚙️ Environment variables

- `HOST` — the address the server listens on (default: `localhost`).
- `PORT` — the port the server listens on (default: `8080`).
- `DATA_FILE` — where polls are mirrored to disk (default: `data/polls.json`, anchored
  to the project root in development). Set to `none` to keep them in memory alone.
- `GH_TOKEN` — a GitHub personal access token, used by CI and agentic workflows.

## 🏗️ Architecture

Three subprojects, as laid out by
[Scala Website Template](https://github.com/SgtSwagrid/scala-website-template).

### [`common`](./common)

Cross-compiled to both the JVM and JavaScript. Holds the entire domain model and the
whole solver, which therefore has no dependency on the server and could equally be run
in the browser.

- [`model`](common/src/main/scala/model) — days, windows, options, participants,
  questions and graded answers. [`Day`](common/src/main/scala/model/Day.scala)
  implements civil-date arithmetic directly rather than depending on `java.time`,
  which has no Scala.js implementation.
- [`solve`](common/src/main/scala/solve) — the pipeline, in order:
  [`Belief`](common/src/main/scala/solve/Belief.scala) turns answers into
  probabilities, [`Ensemble`](common/src/main/scala/solve/Ensemble.scala) samples
  possible turnouts from them,
  [`Forecast`](common/src/main/scala/solve/Forecast.scala) computes each option's
  exact attendance distribution,
  [`Verdict`](common/src/main/scala/solve/Verdict.scala) reads the advice off the
  ensemble, and [`Elicitation`](common/src/main/scala/solve/Elicitation.scala) decides
  what to ask next. [`Analysis`](common/src/main/scala/solve/Analysis.scala) is the
  single entry point.

### [`server`](./server)

A JVM backend serving both the API and the pages, using
[Tapir](https://tapir.softwaremill.com/) on [Netty](https://netty.io/). Polls are held
in memory and mirrored to a JSON file.

### [`client`](./client)

Transpiled to JavaScript and rendered with [Laminar](https://laminar.dev/). Three
views: the landing page, the organiser's dashboard, and the page on which one
participant answers their questions. A participant's page carries nothing about the
poll beyond their own questions — not the guest list, not the weightings, not which
dates are winning.

## ⚠️ Limitations

- **Participants are modelled as independent.** Households who travel together are
  recorded but not correlated, which understates the variance in turnout. A
  participant's own answers *are* correlated across options.
- **Answers are assumed honest and stable.** There is no model of people who say yes
  to everything, nor of availability changing as the date approaches.
- **A link is the access control.** Identifiers are 16 random bytes, so they are
  not guessable, but anyone holding a poll's link can see its dashboard and delete
  it, and anyone holding a participant's link can answer as them. There are no
  accounts. `/metrics` is also served unauthenticated. Fine among friends; not
  fine otherwise.
- **The server does not list polls.** It cannot, without handing every visitor
  every guest list. The landing page remembers only what this browser has opened,
  in local storage, so a lost link is lost.
- **Polls are mirrored to a file, not a database.** Saves are serialised and each
  writes through a scratch file of its own, so concurrent answers are safe, but
  every save rewrites the whole collection. Adequate for a handful of polls
  amended a few dozen times; not for concurrent use at scale.

## 👁️ See also

- This website was made using [Scala Website Template](https://github.com/SgtSwagrid/scala-website-template).
