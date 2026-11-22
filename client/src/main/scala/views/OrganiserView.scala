package com.alecdorrington.client
package views

import com.alecdorrington.client.components.Display.*
import com.alecdorrington.client.net.{Api, Failed}
import com.alecdorrington.common.api.Report
import com.alecdorrington.common.model.*
import com.alecdorrington.common.solve.{Forecast, Round, Verdict}
import com.raquo.laminar.api.L.{*, given}
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel
import scala.util.control.NonFatal

/**
  * The organiser's view of one poll, at URL `/polls/{poll}`.
  *
  * The page is arranged around the decision rather than around the data: what
  * to book, how sure that is, and whether it is worth asking anything further.
  * The guest list and the settings come last, because they are means rather
  * than ends.
  */
@JSExportTopLevel("OrganiserView")
object OrganiserView extends View:

  /**
    * The identifier of the poll being shown.
    *
    * A method rather than a value, because a value is read when this object is
    * initialised, and exporting it to the page makes that happen as the script
    * loads. Under Node, where the test bundle is linked and run, there is no
    * `window` to read an address from, and the whole bundle fails to load.
    */
  private def poll: String = Route.poll.getOrElse("")

  /** How many questions the next round may contain. */
  private val budget = Var(40)

  /** How many questions to put to any one participant. */
  private val each = Var(2)

  /** The report as the server last described it. */
  private val current = Var(Option.empty[Report])

  /** Requests for a fresh report, as a round size to ask for. */
  private val requests = EventBus[(Int, Int)]()

  /**
    * Why the poll could not be loaded, if it could not. Cleared on success:
    * left set, one dropped connection would have the organiser reading "no such
    * poll" about an intact one for as long as the page stayed open.
    */
  private val failed = Var(Option.empty[Failed])

  override protected def content = div(
    cls("page"),

    // Reload whenever the round size changes, or when asked to.
    reports -->
      (report =>
        failed.set(None)
        current.set(Some(report))
      ),
    onMountCallback(_ => reload()),
    budget.signal.changes.map(size => (size, each.now())) --> requests.writer,
    each.signal.changes.map(per => (budget.now(), per)) --> requests.writer,

    // Kept outside the part that is rebuilt whenever a report arrives, so that
    // the field being typed into is not replaced mid-keystroke.
    rounds,
    child <--
      current
        .signal
        .combineWith(failed.signal)
        .map: (report, broken) =>
          (report, broken) match
            case (_, Some(reason)) if reason.absent => missing
            case (None, Some(reason))               => unreachable(reason)
            case (None, None)      => div(cls("lede"), "Working it out…")
            case (Some(report), _) => dashboard(report),
  )

  /** Where every fresh report goes. */
  private val received: Observer[Report] = current.writer.contramap(Some(_))

  /** Asks for a fresh report at the size currently chosen. */
  private def reload(): Unit = requests.emit((budget.now(), each.now()))

  /**
    * Fresh reports, one for each request, with a failure recorded rather than
    * thrown so that a deleted poll shows a message instead of a blank page.
    */
  private def reports: EventStream[Report] = requests
    .events
    .flatMapSwitch: (size, per) =>
      Api
        .read(poll, size, per)
        .recover:
          case reason: Failed =>
            failed.set(Some(reason))
            None
          case _ =>
            failed.set(Some(Failed(0, "read")))
            None

  /** The page shown when there is no such poll. */
  private def missing: HtmlElement = div(
    h1("No such poll"),
    p(
      cls("lede"),
      "It may have been deleted. ",
      a(href("/"), "Start again"),
    ),
  )

  /** The page shown when the poll may well exist but could not be reached. */
  private def unreachable(reason: Failed): HtmlElement = div(
    h1("Could not reach the server"),
    p(
      cls("lede"),
      "The poll is most likely fine; the request did not get through" +
        (if reason.status == 0 then "" else s" (${ reason.status })") + ".",
    ),
    button("Try again", onClick --> (_ => reload())),
  )

  /** How large the next round should be. Client-side only, so no reload here. */
  private def rounds: HtmlElement = div(
    cls("panel"),
    marginBottom("18px"),
    div(
      cls("grid"),
      counter("Questions per round", budget, 1, 200),
      counter("Questions per guest", each, 1, 20),
    ),
  )

  /** The whole dashboard for a loaded report. */
  private def dashboard(report: Report): HtmlElement = div(
    masthead(
      report.poll.title,
      s"${ report.poll.slots.size } candidate options across " +
        s"${ report.poll.slots.map(_.venue).distinct.size } venues, " +
        s"${ report.poll.participants.size } guests.",
    )(
      badge(
        if report.poll.roundsSent == 0 then "Nothing asked yet"
        else s"Round ${ report.poll.roundsSent } sent",
      ),
      a(href("/"), cls("small"), "All polls"),
    ),
    div(
      cls("stack"),
      advice(report),
      ranking(report),
      asking(report),
      guests(report),
      settings(report),
    ),
  )

  /** What to book, and whether to book it yet. */
  private def advice(report: Report): HtmlElement =
    val verdict = report.analysis.verdict
    panel(
      "The recommendation",
      "What to choose if the choice had to be made now.",
    )(
      verdict.best match
        case None => p(
            cls("faint"),
            "This poll has no candidate options.",
          )
        case Some(best) => div(
            h3(best.slot.venue, fontSize("21px")),
            p(cls("faint"), best.slot.window.show),
            div(
              cls("figures"),
              marginTop("16px"),
              statistic(
                percent(verdict.confidence.getOrElse(best.slot.id, 0.0)),
                "chance it is best",
              ),
              statistic(
                decimal(best.attendance),
                "expected guests",
              ),
              statistic(
                best.slot.capacity.toString,
                "capacity",
              ),
              statistic(
                percent(best.risk),
                "chance of overflow",
              ),
              statistic(whole(best.slot.cost), "cost"),
            ),
            div(marginTop("16px"), attendanceBar(best)),
            div(marginTop("18px"), stopping(verdict)),
          ),
    )

  /** A bar showing expected attendance against a venue's capacity. */
  private def attendanceBar(forecast: Forecast): HtmlElement = div(
    div(
      cls("spread"),
      cls("tiny"),
      cls("faint"),
      span("Expected attendance against capacity"),
      span(
        cls("numeric"),
        percent(forecast.utilisation),
      ),
    ),
    bar(
      forecast.utilisation,
      risk(forecast.risk),
    ),
  )

  /**
    * Whether to ask anything further.
    *
    * The value of information is the whole basis of the advice, so it is stated
    * in the units the organiser cares about: guests, not bits.
    */
  private def stopping(verdict: Verdict): HtmlElement =
    if verdict.settled then
      div(
        cls("note"),
        cls("settled"),
        strong("Stop asking and book. "),
        "Having everybody's answers would improve on this choice by only " +
          s"${ decimal(verdict.information) } guests, which is less than " +
          "another round of questions is worth.",
        irreducible(verdict),
      )
    else
      div(
        cls("note"),
        strong("Worth another round. "),
        "Having everybody's answers would be worth about " +
          s"${ decimal(verdict.information) } more guests than choosing now. " +
          "That is the most any further asking could gain, so it is the " +
          "number to watch: once it is small, stop.",
        irreducible(verdict),
      )

  /**
    * A note on the part of the uncertainty that asking cannot touch, shown only
    * where it is large enough to matter. Without it the headline figure looks
    * unaccountably small on a poll most people have already answered.
    *
    * @param verdict
    *   The advice whose irreducible part is to be described.
    *
    * @return
    *   An element describing it, or nothing where it is negligible.
    */
  private def irreducible(verdict: Verdict): Modifier[HtmlElement] = Option
    .when(verdict.noise > 0.1)(div(
      cls("tiny"),
      cls("faint"),
      marginTop("8px"),
      s"A further ${ decimal(verdict.noise) } guests' worth of uncertainty " +
        "is down to people who can come not turning up, which no question " +
        "would settle. It is excluded from the figure above.",
    ))

  /** How the options stand against one another. */
  private def ranking(report: Report): HtmlElement =
    val verdict = report.analysis.verdict
    val leader  = verdict.recommended.map(_.id)
    panel(
      "How the options stand",
      "Chance of being best is the share of simulated turnouts in which an " +
        "option comes out on top. Shortfall is how many guests choosing it " +
        "would be expected to cost against whichever option turns out best.",
    )(table(
      thead(tr(
        th("Venue"),
        th("Dates"),
        th(cls("figure-column"), "Best"),
        th(cls("figure-column"), "Guests"),
        th(cls("figure-column"), "Of capacity"),
        th(cls("figure-column"), "Overflow"),
        th(cls("figure-column"), "Shortfall"),
      )),
      tbody(
        verdict
          .forecasts
          .take(8)
          .map: forecast =>
            tr(
              cls("leading") := leader.contains(forecast.slot.id),
              td(forecast.slot.venue),
              td(
                cls("small"),
                forecast.slot.window.showBrief,
              ),
              td(
                cls("figure-column"),
                percent(verdict.confidence.getOrElse(forecast.slot.id, 0.0)),
              ),
              td(
                cls("figure-column"),
                decimal(forecast.attendance),
              ),
              td(
                cls("figure-column"),
                percent(forecast.utilisation),
              ),
              td(
                cls("figure-column"),
                percent(forecast.risk),
              ),
              td(
                cls("figure-column"),
                decimal(verdict.regret.getOrElse(forecast.slot.id, 0.0)),
              ),
            ),
      ),
    ))

  /** The next round of questions, and the means of sending it. */
  private def asking(report: Report): HtmlElement =
    val next = report.analysis.round
    panel(
      "What to ask next",
      "Chosen to change the decision as much as possible per question asked, " +
        "preferring broad periods to named dates so that nobody is asked to " +
        "hold a weekend that may not happen.",
    )(
      if next.enquiries.isEmpty then
        p(
          cls("faint"),
          "There is nothing worth asking. Either everyone has answered, or no " +
            "answer would change the choice.",
        )
      else
        div(
          scale(next),
          despatch(next),
          h3(
            "The questions",
            marginTop("22px"),
            marginBottom("8px"),
          ),
          div(cls("scroll"), questions(report, next)),
        ),
    )

  /** How large the next round is, and how much of the doubt it would settle. */
  private def scale(next: Round): HtmlElement = div(
    cls("figures"),
    statistic(
      next.enquiries.size.toString,
      "questions",
    ),
    statistic(
      next.recipients.toString,
      "guests to contact",
    ),
    statistic(
      decimal(next.enquiries.size.toDouble / math.max(1, next.recipients)),
      "questions each",
    ),
    statistic(
      percent(next.coverage),
      "of what's left to learn",
    ),
  )

  /** The means of sending the round out. */
  private def despatch(next: Round): HtmlElement = div(
    cls("row"),
    marginTop("18px"),
    button(
      s"Send these ${ next.enquiries.size } questions",
      onClick.flatMapTo(
        Api
          .sendRound(poll, budget.now(), each.now())
          .recover:
            case reason: Failed =>
              failed.set(Some(reason))
              None,
      ) --> received,
    ),
    span(
      cls("small"),
      cls("faint"),
      "Records the questions against each guest, so their link shows exactly " +
        "what was sent.",
    ),
  )

  /** Each question in the round, with what it is worth. */
  private def questions(report: Report, next: Round): HtmlElement =
    val most = next.enquiries.map(_.worth).maxOption.getOrElse(1.0)
    table(
      thead(tr(
        th("Guest"),
        th("Question"),
        figureHeading("Bits"),
        th(width("90px"), "Worth"),
      )),
      tbody(
        next
          .enquiries
          .map: enquiry =>
            tr(
              td(
                report
                  .poll
                  .participantsById
                  .get(enquiry.participant)
                  .map(_.name)
                  .getOrElse("Unknown"),
              ),
              td(
                cls("small"),
                enquiry
                  .question
                  .prompt(
                    report.poll.slotsById,
                    report.poll.length,
                  ),
              ),
              figures(precise(enquiry.worth), cls("tiny")),
              td(bar(enquiry.worth / most)),
            ),
      ),
    )

  /** The guest list, with each guest's link and standing. */
  private def guests(report: Report): HtmlElement =
    val belief = report.analysis.verdict.recommended
    panel(
      "Guests",
      "Each guest answers at their own link. Nothing on their page reveals the " +
        "guest list, the weightings, or which dates are winning.",
    )(div(
      cls("scroll"),
      table(
        thead(tr(
          th("Guest"),
          th(cls("figure-column"), "Weight"),
          th(cls("figure-column"), "Answered"),
          th(cls("figure-column"), "Waiting"),
          th("Link"),
        )),
        tbody(
          report
            .poll
            .participants
            .map: participant =>
              val link = Route.answering(poll, participant.id.value)
              tr(
                td(participant.name),
                td(
                  cls("figure-column"),
                  decimal(participant.weight),
                ),
                td(
                  cls("figure-column"),
                  report.poll.responsesBy(participant.id).size.toString,
                ),
                td(
                  cls("figure-column"),
                  report.poll.pendingBy(participant.id).size.toString,
                ),
                td(
                  cls("row"),
                  a(href(link), cls("small"), "open"),
                  button(
                    cls("quiet"),
                    cls("tiny"),
                    padding("2px 8px"),
                    "copy",
                    onClick --> (_ => copy(link)),
                  ),
                ),
              ),
        ),
      ),
    ))

  /** The settings governing both the advice and the questions. */
  private def settings(report: Report): HtmlElement =
    val edited = Var(report.poll.objective)
    panel(
      "Settings",
      "What counts as a good choice. Worth a moment's thought: the cost of " +
        "naming a date in particular decides whether dates get named early.",
    )(
      div(
        cls("grid"),
        tuning(
          "Cost of naming a date",
          edited,
          0.5,
          "How much dearer it is to ask about a named date than a whole " +
            "period. Above one, broad questions come first.",
        )(_.discretion)((objective, value) =>
          objective.copy(discretion = value),
        ),
        tuning(
          "Chance any date suits",
          edited,
          0.05,
          "What to assume before anyone answers. Lower it when attending is a " +
            "real imposition, as for long-haul travel.",
        )(_.prior)((objective, value) => objective.copy(prior = value)),
        tuning(
          "Penalty per guest over capacity",
          edited,
          0.5,
          "Should exceed one: turning away a guest who has accepted costs " +
            "more than never inviting them.",
        )(_.overflowWeight)((objective, value) =>
          objective.copy(overflowWeight = value),
        ),
        tuning(
          "Guests worth one unit of cost",
          edited,
          0.001,
          "Leave at zero to ignore cost and choose purely on attendance.",
        )(_.costWeight)((objective, value) =>
          objective.copy(costWeight = value),
        ),
      ),
      div(
        cls("row"),
        marginTop("18px"),
        button(
          "Apply",
          // Applied together, as one objective. Sending each field as it was
          // changed meant sending four whole objectives, each built from
          // whatever had last come back: change two in quick succession and the
          // second, having started from a copy taken before the first landed,
          // quietly undid it.
          disabled <-- edited.signal.map(_ == report.poll.objective),
          onClick.flatMap(_ =>
            Api
              .retarget(poll, edited.now())
              .recover:
                case reason: Failed =>
                  failed.set(Some(reason))
                  None,
          ) --> (_ => reload()),
        ),
        child.maybe <--
          edited
            .signal
            .map(objective =>
              Option.when(objective != report.poll.objective)(span(
                cls("small"),
                cls("faint"),
                "Not yet applied.",
              )),
            ),
      ),
    )

  /** A whole-number setting held on the client alone. */
  private def counter
    (
      name: String,
      held: Var[Int],
      least: Int,
      most: Int,
    )
    : HtmlElement = label(
    cls("field"),
    name,
    input(
      typ("number"),
      stepAttr("1"),
      minAttr(least.toString),
      maxAttr(most.toString),
      // Committed on leaving the field, not on every keystroke. Each change
      // costs a full solve on the server, so typing "120" would have asked for
      // three of them, at one, twelve and a hundred and twenty.
      value <-- held.signal.map(_.toString),
      onChange.mapToValue.map(_.toIntOption) -->
        (entered => entered.map(math.max(least, _).min(most)).foreach(held.set)),
    ),
  )

  /**
    * A setting belonging to the poll's objective, saved to the server on
    * change.
    */
  private def tuning
    (
      name: String,
      held: Var[Objective],
      step: Double,
      hint: String,
    )
    (read: Objective => Double)
    (revise: (Objective, Double) => Objective)
    : HtmlElement = label(
    cls("field"),
    name,
    input(
      typ("number"),
      stepAttr(step.toString),
      minAttr("0"),
      value(read(held.now()).toString),
      onChange
        .mapToValue
        .map(_.toDoubleOption)
        .collect { case Some(entered) => entered } -->
        (entered => held.update(revise(_, entered))),
    ),
    span(cls("tiny"), cls("faint"), hint),
  )

  /**
    * Puts text on the clipboard.
    *
    * Reached through [[js.Dynamic]] rather than a typed binding, because the
    * clipboard is absent on insecure origins and older browsers, and a failure
    * to copy a link should not take the page down with it.
    */
  private def copy(text: String): Unit =
    try js.Dynamic.global.navigator.clipboard.writeText(text)
    catch case NonFatal(_) => ()
