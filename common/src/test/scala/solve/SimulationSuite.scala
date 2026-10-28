package com.alecdorrington.common
package solve

import com.alecdorrington.common.model.*
import com.alecdorrington.common.solve.Fixture.*
import munit.FunSuite
import scala.concurrent.duration.{Duration, DurationInt}
import scala.util.Random

/**
  * Tests the solver end to end against participants whose availability is fixed
  * in advance and hidden from it, by conducting a few rounds of questions and
  * asking whether the slot it settles on is in fact a good one.
  *
  * This is the claim the whole design rests on: that a handful of rounds, each
  * asking each participant only one or two questions, suffices to identify a
  * near-best slot out of many. Everything else is only machinery.
  *
  * Every claim here is made across several hidden guest lists rather than one.
  * A single list flatters or damns the solver by luck — quality at three rounds
  * ranges from 71% to 100% of the best available across the lists below — so an
  * assertion tuned to one of them says nothing about the method.
  */
class SimulationSuite extends FunSuite:

  /**
    * Run on the JVM alone. These are minutes of arithmetic that re-check what
    * the JVM run has already shown, and on Scala.js they are slow enough to
    * exceed the timeout and fail the build for no fault of the solver's.
    */
  override def munitIgnore: Boolean = !Fixture.onJvm

  /** Generous, because a loaded build machine is much slower than a desktop. */
  override def munitTimeout: Duration = 10.minutes

  /** The hidden guest lists to try, each the seed that generates one. */
  private val worlds = List(1987L, 4L, 12L, 99L, 2027L)

  /**
    * How many rounds to conduct.
    *
    * Four rounds average 85% of the best available value on the guest lists
    * below and find the very best slot on two of the five; a fifth round
    * carries that to 95% and three of five. The claim made here is therefore
    * about five rounds, because that is the one the numbers support.
    */
  private val rounds = 5

  /** How many questions each round may contain. */
  private val budget = 30

  /** Two venues, each free on a series of weekends across the summer. */
  private val slots = Slot.enumerate(
    venue = "Alpine Hut",
    openings = List(window("2027-05-07", "2027-09-26")),
    capacity = 70,
    cost = 4000,
    length = 3,
    stride = 21,
  ) ++ Slot.enumerate(
    venue = "Lake Lodge",
    openings = List(window("2027-06-04", "2027-08-28")),
    capacity = 34,
    cost = 2500,
    length = 3,
    stride = 21,
  )

  /** Forty guests, four of whom must be there. */
  private val guests = (1 to 36)
    .map(index => participant(s"guest$index"))
    .toList ++ (1 to 4)
      .map(index => participant(s"family$index", weight = 8.0))
      .toList

  private val base = poll(slots, guests)

  /**
    * One hidden truth of who could attend what.
    *
    * Each guest favours one month, and is usually free on its weekends and
    * usually not on others, which is roughly how travel to the far side of the
    * world behaves: it is governed by a season rather than by single dates. A
    * tenth of the guests cannot come at all.
    *
    * @param seed
    *   Which hidden guest list to generate.
    *
    * @return
    *   Whether each guest could attend each slot, by guest and then slot.
    */
  private def truth(seed: Long): Vector[Vector[Boolean]] =
    val random = Random(seed)
    guests
      .toVector
      .map: _ =>
        // June is the popular month, so a clear favourite should emerge.
        val favoured = random.shuffle(List(5, 6, 6, 6, 7, 8)).head
        val willing  = random.nextDouble() > 0.1
        slots
          .toVector
          .map: candidate =>
            val chance =
              if candidate.window.start.month == favoured then 0.85 else 0.2
            willing && random.nextDouble() < chance

  /** The true value of a slot, were everybody's availability known exactly. */
  private def value
    (
      hidden: Vector[Vector[Boolean]],
      slot: Int,
    )
    : Double =
    val attending = guests.indices.filter(hidden(_)(slot))
    attending.map(guests(_).weight).sum -
      base.objective.costWeight * slots(slot).cost -
      base.objective.overflowWeight *
      math.max(0, attending.size - slots(slot).capacity)

  /** The answer a guest would truthfully give to a question. */
  private def reply
    (
      hidden: Vector[Vector[Boolean]],
      guest: Int,
      question: Question,
    )
    : Availability =
    val bearing = slots.indices.filter(slot => question.bearsOn(slots(slot)))
    if bearing.exists(hidden(guest)) then Availability.Yes else Availability.No

  /** The advice on a poll, as each round takes it. */
  private def analyse(current: Poll): Analysis =
    Analysis.of(current, budget, perParticipant = 2)

  /** The truthful answers to the questions that some advice would ask. */
  private def answers
    (
      hidden: Vector[Vector[Boolean]],
      advice: Analysis,
    )
    : List[Response] = advice
    .round
    .enquiries
    .map: enquiry =>
      val guest = guests.indexWhere(_.id == enquiry.participant)
      Response(
        enquiry.participant,
        enquiry.question,
        reply(hidden, guest, enquiry.question),
      )

  /**
    * Conducts the full course of rounds against one hidden truth.
    *
    * @return
    *   The poll before each round and after the last, each with its advice.
    */
  private def elicit(hidden: Vector[Vector[Boolean]]): List[(Poll, Analysis)] =
    List.iterate((base, analyse(base)), rounds + 1): (current, advice) =>
      val next = current.record(answers(hidden, advice))
      (next, analyse(next))

  /**
    * Each hidden guest list, with the course of rounds conducted against it.
    *
    * Worked out once and shared by the tests below, as conducting the rounds is
    * nearly all of their cost.
    */
  private lazy val courses
    : List[(Long, Vector[Vector[Boolean]], List[(Poll, Analysis)])] = worlds
    .map: seed =>
      val hidden = truth(seed)
      (seed, hidden, elicit(hidden))

  /**
    * How good the solver's choice proved to be against one hidden guest list.
    *
    * @param hidden
    *   The guest list's hidden truth.
    *
    * @param outcome
    *   The advice the solver finished with.
    *
    * @return
    *   The chosen slot's share of the best available value, and its true rank.
    */
  private def quality
    (
      hidden: Vector[Vector[Boolean]],
      outcome: Analysis,
    )
    : (Double, Int) =
    val chosen = slots.indexWhere(_.id == outcome.verdict.recommended.get.id)
    val values = slots.indices.map(value(hidden, _))
    (
      values(chosen) / values.max,
      values.sorted.reverse.indexOf(values(chosen)) + 1,
    )

  test(
    s"$rounds short rounds find a near-best slot, across several guest lists",
  ):

    val outcomes = courses.map: (seed, hidden, course) =>
      val outcome       = course.last._2
      val (share, rank) = quality(hidden, outcome)
      (seed, share, rank, outcome)
    val shares = outcomes.map(_._2)
    val mean   = shares.sum / shares.size

    println(f"\n  ${ slots.size } slots, ${ guests
        .size } guests," + f" $rounds rounds of $budget questions")
    println("  guest list   of best   rank   still to learn")
    outcomes.foreach: (seed, share, rank, outcome) =>
      println(
        f"  $seed%10d   ${ 100 * share }%5.0f%%   #$rank%-4d  " +
          f"${ outcome.verdict.information }%5.2f",
      )
    println(f"  mean ${ 100 * mean }%.0f%%\n")

    assert(
      shares.min > 0.75,
      f"the worst guest list gave only ${ 100 * shares.min }%.0f%% of the best",
    )
    assert(
      mean > 0.9,
      f"the mean was only ${ 100 * mean }%.0f%%",
    )
    assert(
      shares.count(_ >= 0.99) >= 2,
      s"only ${ shares.count(_ >= 0.99) } of ${ shares
          .size } lists found the very best slot",
    )

  test(
    "asking narrows what is left to learn and grows confidence in a choice far better than an uninformed one, asking nobody much",
  ):
    val (_, hidden, course) = courses.head
    val first               = course(1)._2
    val (finished, outcome) = course.last

    val values     = slots.indices.map(value(hidden, _))
    val middle     = values.sum / values.size
    val (share, _) = quality(hidden, outcome)
    assert(
      share * values.max > middle + 0.6 * (values.max - middle),
      f"${ share * values.max }%.1f against a mean of $middle%.1f",
    )

    // Compared start to finish rather than round by round. The value of
    // information is not monotone and should not be asserted to be: learning
    // that many guests are free in one month widens the gap between the slots,
    // so what it would cost to choose wrongly can rise for a round before it
    // falls.
    assert(
      outcome.verdict.information < 0.5 * first.verdict.information,
      s"${ outcome.verdict.information } should be well below " +
        s"${ first.verdict.information }",
    )

    def leading(analysis: Analysis): Double =
      analysis.verdict.confidence.values.max

    assert(
      leading(outcome) > leading(first),
      s"${ leading(outcome) } should exceed ${ leading(first) }",
    )

    val burden = finished.responses.groupMapReduce(_.participant)(_ => 1)(_ + _)
    // Two per round is the cap set above, so this is the most anyone can be
    // asked; the point of the check is that nobody is asked beyond it.
    assert(
      burden.values.max <= 2 * rounds,
      s"one guest was asked ${ burden.values.max }",
    )
    assert(
      finished.responses.sizeIs <= rounds * budget,
      s"${ finished.responses.size } questions in all",
    )

  test("a wide field of slots is narrowed to a near-best one"):
    // A separate fixture because the one above is too forgiving to catch a real
    // regression. With eleven slots, a solver choosing almost at random still
    // lands near the best by luck; with thirty it does not. Sharing tied worlds
    // out among the options that tied for them passed every other test here and
    // took this one from third place of thirty to nineteenth.
    val many = Slot.enumerate(
      venue = "Ridge",
      openings = List(window("2027-05-14", "2027-09-12")),
      capacity = 64,
      cost = 0,
      length = 3,
      stride = 7,
    )
    val crowd = (1 to 28).map(index => participant(s"guest$index")).toList ++
      (1 to 4).map(index => participant(s"family$index", weight = 10.0)).toList
    val subject = poll(
      many,
      crowd,
      Objective(prior = 0.25, discretion = 3.0),
    )

    val random = Random(31L)
    val hidden = crowd
      .toVector
      .map: _ =>
        val favoured = random.shuffle(List(5, 6, 7, 7, 8, 8)).head
        val willing  = random.nextDouble() > 0.1
        many
          .toVector
          .map: candidate =>
            willing &&
            random.nextDouble() <
              (if candidate.window.start.month == favoured then 0.85 else 0.2)

    def worth(slot: Int): Double = crowd
      .indices
      .filter(hidden(_)(slot))
      .map(crowd(_).weight)
      .sum

    val finished = (1 to 4).foldLeft(subject): (current, _) =>
      val advice = Analysis.of(current, 40, perParticipant = 2)
      current.record(
        advice
          .round
          .enquiries
          .map: enquiry =>
            val guest   = crowd.indexWhere(_.id == enquiry.participant)
            val bearing =
              many.indices.filter(slot => enquiry.question.bearsOn(many(slot)))
            Response(
              enquiry.participant,
              enquiry.question,
              if bearing.exists(hidden(guest)) then Availability.Yes
              else Availability.No,
            ),
      )

    val outcome = Analysis.of(finished, 40, perParticipant = 2)
    val chosen  = many.indexWhere(_.id == outcome.verdict.recommended.get.id)
    val values  = many.indices.map(worth)
    val rank    = values.sorted.reverse.indexOf(values(chosen)) + 1

    println(f"\n  ${ many.size } slots: chose #$rank at " + f"${ 100 *
          values(chosen) / values.max }%.0f%% of the best\n")
    assert(
      rank <= 5,
      s"chose the slot ranked #$rank of ${ many.size }",
    )
    assert(
      values(chosen) > 0.85 * values.max,
      f"chose a slot worth ${ values(chosen) }%.1f against ${ values.max }%.1f",
    )
