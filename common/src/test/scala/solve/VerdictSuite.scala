package com.alecdorrington.common
package solve

import com.alecdorrington.common.model.*
import com.alecdorrington.common.solve.Fixture.*
import munit.FunSuite

class VerdictSuite extends FunSuite:

  private val good = slot("Good", "2027-06-11")
  private val bad  = slot("Bad", "2027-06-18")

  private val folk = guests(12)

  private def verdict(responses: List[Response]): Verdict =
    advise(poll(List(good, bad), folk).copy(responses = responses))

  test(
    "knowing nothing leaves no slot looking certain or out of contention, with the recommendation's regret as the value of information",
  ):
    // Note that two interchangeable slots genuinely leave little to learn: a
    // dozen guests split between them either way, so whichever is chosen is
    // about as good. It takes a real field of slots before asking pays, which is
    // what the test of the irreducible floor below checks, on a poll nobody has
    // answered.
    val advice = verdict(List.empty)
    assertEqualsDouble(
      advice.confidence.values.sum,
      1.0,
      1.0e-9,
    )
    assertEquals(advice.confidence.size, 2)
    assert(
      advice.confidence.values.max < 0.9,
      "no slot should look certain",
    )
    assertEquals(advice.contenders().size, 2)
    assert(
      advice.information > 0.0,
      advice.information,
    )
    assertEqualsDouble(
      advice.information,
      advice.regret(advice.recommended.get.id),
      1.0e-9,
    )
    assert(advice.regret.values.forall(_ >= -1.0e-9))
    assert(advice.regret.values.min >= 0.0)

  test(
    "a unanimous answer settles the choice, leaving the other slot out of contention",
  ):
    val answers = folk.flatMap: guest =>
      List(
        says(guest.name, good, Availability.Yes),
        says(guest.name, bad, Availability.No),
      )
    val advice = verdict(answers)
    assertEquals(
      advice.recommended.map(_.venue),
      Some("Good"),
    )
    assert(
      advice.confidence(good.id) > 0.98,
      advice.confidence(good.id),
    )
    assert(
      advice.information < Verdict.Tolerance,
      advice.information,
    )
    assert(advice.settled)
    assertEquals(
      advice.contenders().map(_.venue),
      List("Good"),
    )

  test("forecasts are ordered best first and agree with the recommendation"):
    val answers = folk.map(guest => says(guest.name, good, Availability.Yes))
    val advice  = verdict(answers)
    val scores  = advice.forecasts.map(_.score)
    assertEquals(scores, scores.sorted.reverse)
    assertEquals(
      advice.forecasts.head.slot.id,
      advice.recommended.get.id,
    )
    assertEquals(
      advice.best.map(_.slot.venue),
      Some("Good"),
    )

  test("popularity does not redeem a venue that cannot hold everyone"):
    val cramped = slot("Cramped", "2027-06-11", capacity = 3)
    val roomy   = slot("Roomy", "2027-06-18", capacity = 50)
    val answers = folk.flatMap: guest =>
      List(
        says(guest.name, cramped, Availability.Yes),
        says(
          guest.name,
          roomy,
          Availability.Probably,
        ),
      )
    val advice =
      advise(poll(List(cramped, roomy), folk).copy(responses = answers))
    assertEquals(
      advice.recommended.map(_.venue),
      Some("Roomy"),
    )

  test("an empty poll is handled without complaint"):
    val advice = advise(poll(List.empty, List.empty))
    assertEquals(advice.recommended, None)
    assertEquals(advice.forecasts, List.empty)
    assertEqualsDouble(advice.information, 0.0, 1.0e-9)

  test(
    "the irreducible floor is measured and taken out of the advice, without settling a poll nobody has answered",
  ):
    // Everybody has answered directly about every slot, so nothing is left to
    // learn. What spread remains is the chance that a guest who says they can
    // come does not, which no further question could resolve.
    val many  = (1 to 32).map(index => participant(s"guest$index")).toList
    val dates = (0 until 24)
      .map(index =>
        slot(
          "Venue",
          Day.ofEpochDay(20940 + 7 * index).iso,
        ),
      )
      .toList
    val answers =
      for
        person <- many
        date   <- dates
      yield says(person.name, date, Availability.Yes)

    val advice = advise(poll(dates, many).copy(responses = answers))

    assert(
      advice.noise > Verdict.Tolerance,
      s"the floor is ${ advice.noise }",
    )
    assert(
      advice.settled,
      s"nothing is left to learn, yet information is ${ advice.information }",
    )

    val unanswered = advise(poll(dates, many))
    assert(
      !unanswered.settled,
      s"information is only ${ unanswered.information }",
    )
