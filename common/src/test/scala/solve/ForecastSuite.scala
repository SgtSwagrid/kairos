package com.alecdorrington.common
package solve

import com.alecdorrington.common.model.*
import com.alecdorrington.common.solve.Fixture.*
import munit.FunSuite

class ForecastSuite extends FunSuite:

  test(
    "the attendance distribution is a distribution whose mean is the sum of the probabilities, binomial where they are equal, and all on one outcome where they are certain",
  ):
    val probabilities = List(0.1, 0.35, 0.5, 0.72, 0.9, 0.99)
    val distribution  = Forecast.poissonBinomial(probabilities)
    assertEquals(
      distribution.size,
      probabilities.size + 1,
    )
    assertEqualsDouble(distribution.sum, 1.0, 1.0e-9)
    assert(distribution.forall(_ >= 0.0))
    val mean = distribution.zipWithIndex.map((p, count) => p * count).sum
    assertEqualsDouble(mean, probabilities.sum, 1.0e-9)

    val trials      = 8
    val probability = 0.3
    val binomial    = Forecast.poissonBinomial(List.fill(trials)(probability))
    val expected    = (0 to trials).map: successes =>
      val ways = (1 to successes).foldLeft(1.0): (product, step) =>
        product * (trials - step + 1) / step
      ways * math.pow(probability, successes) *
        math.pow(1 - probability, trials - successes)
    binomial
      .lazyZip(expected)
      .foreach((actual, want) => assertEqualsDouble(actual, want, 1.0e-9))

    val certain = Forecast.poissonBinomial(List(1.0, 1.0, 0.0))
    assertEqualsDouble(certain(2), 1.0, 1.0e-9)

  test(
    "an overfull venue is penalised and flagged as risky, and an impossible capacity does not make risk and overflow disagree",
  ):
    val cramped = slot("Cramped", "2027-06-11", capacity = 4)
    val guests  = (1 to 10).map(index => participant(s"guest$index")).toList
    val answers =
      guests.map(guest => says(guest.name, cramped, Availability.Yes))
    val subject  = poll(List(cramped), guests).copy(responses = answers)
    val forecast = Forecast.of(
      Belief.from(subject),
      0,
      subject.objective,
    )

    assert(
      forecast.attendance > 9.0,
      "nearly everybody attends",
    )
    assert(
      forecast.risk > 0.99,
      "and the venue is certain to overflow",
    )
    assert(
      forecast.overflow > 5.0,
      "by about six heads",
    )
    assert(
      forecast.score < 0.0,
      "which is worse than not booking it",
    )
    assert(forecast.utilisation > 2.0)

    List(-5, 0, Int.MaxValue).foreach: capacity =>
      val odd      = slot("Odd", "2027-06-11", capacity = capacity)
      val subject  = poll(List(odd), guests.take(6))
      val forecast = Forecast.of(
        Belief.from(subject),
        0,
        subject.objective,
      )
      assert(
        forecast.risk >= 0.0 && forecast.risk <= 1.0,
        s"capacity $capacity gave a risk of ${ forecast.risk }",
      )
      assert(
        forecast.risk > 0.0 || forecast.overflow == 0.0,
        s"capacity $capacity is never overrun yet overflows by ${ forecast
            .overflow }",
      )

  test("cost counts against a slot in proportion to its weight"):
    val free    = slot("Free", "2027-06-11", cost = 0)
    val pricey  = slot("Pricey", "2027-06-18", cost = 10)
    val guests  = (1 to 6).map(index => participant(s"guest$index")).toList
    val costly  = Objective(costWeight = 0.5)
    val subject = poll(List(free, pricey), guests, costly)
    val beliefs = Belief.from(subject)
    val gap     = Forecast.of(beliefs, 0, costly).score -
      Forecast.of(beliefs, 1, costly).score
    assertEqualsDouble(gap, 5.0, 1.0e-9)
