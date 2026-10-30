package com.alecdorrington.common
package solve

import com.alecdorrington.common.model.*
import com.alecdorrington.common.solve.Fixture.*
import munit.FunSuite

class BeliefSuite extends FunSuite:

  private val june  = window("2027-06-01", "2027-06-30")
  private val early = window("2027-06-01", "2027-06-15")

  private val weekend = slot("Hut", "2027-06-11")
  private val later   = slot("Hut", "2027-06-25")

  private val base = poll(
    List(weekend, later),
    List(participant("ada"), participant("bob")),
  )

  private def belief(responses: Response*): Belief =
    Belief.from(base.copy(responses = responses.toList))

  test("with nothing answered, every entry sits at the prior, in full doubt"):
    val beliefs = belief()
    assertEquals(beliefs.size, 2)
    assertEquals(beliefs.width, 2)
    beliefs
      .participants
      .indices
      .foreach: person =>
        beliefs
          .slots
          .indices
          .foreach: candidate =>
            assertEquals(
              beliefs(person, candidate),
              base.objective.prior,
            )
    assertEquals(beliefs.doubt(0, 0), 1.0)

  test(
    "a direct answer is taken at face value, leaving little doubt, and overrides a pooled one",
  ):
    val beliefs = belief(says("ada", weekend, Availability.Yes))
    assertEquals(
      beliefs(0, 0),
      Availability.Yes.probability,
    )
    assertEquals(
      beliefs(0, 1),
      base.objective.prior,
      "other slots untouched",
    )
    assertEquals(
      beliefs(1, 0),
      base.objective.prior,
      "other people untouched",
    )
    val settled = belief(says("ada", weekend, Availability.No))
    assert(
      settled.doubt(0, 0) < 0.15,
      "a refusal leaves little doubt",
    )
    val overridden = belief(
      saysOf("ada", june, Availability.Yes),
      says("ada", weekend, Availability.No),
    )
    assertEquals(
      overridden(0, 0),
      Availability.No.probability,
    )
    assert(
      overridden(0, 1) > base.objective.prior,
      "the pooled answer still holds",
    )

  test(
    "a pooled refusal rules out every slot in the window, even at a high prior, and the narrowest pooled answer wins where several apply",
  ):
    val beliefs = belief(saysOf("ada", june, Availability.No))
    assertEquals(
      beliefs(0, 0),
      Availability.No.probability,
    )
    assertEquals(
      beliefs(0, 1),
      Availability.No.probability,
    )
    val eager = base.copy(objective = Objective(prior = 0.96))
    val wide  = Belief.from(
      eager.copy(responses = List(saysOf("ada", june, Availability.No))),
    )
    assertEquals(wide(0, 0), Availability.No.probability)
    val narrowest = belief(
      saysOf("ada", june, Availability.Yes),
      saysOf("ada", early, Availability.No),
    )
    assertEquals(
      narrowest(0, 0),
      Availability.No.probability,
    )

  test(
    "a pooled acceptance is discounted, but still encouraging, and less so the narrower it is, even at a high prior",
  ):
    val beliefs = belief(saysOf("ada", june, Availability.Yes))
    val stated  = Availability.Yes.probability
    assert(
      beliefs(0, 0) > base.objective.prior,
      "should be encouraging",
    )
    assert(
      beliefs(0, 0) < stated,
      "should not be taken at face value",
    )
    val wide   = beliefs(0, 0)
    val narrow = belief(saysOf("ada", early, Availability.Yes))(0, 0)
    assert(
      narrow > wide,
      s"$narrow should exceed $wide",
    )
    // Drawing the line at the prior rather than at the neutral grade made this
    // unreachable: above a prior of 0.95 every grade counted as negative, the
    // dilution below became dead code, and pooled answers of any width
    // transferred at face value without anything saying so.
    val eager = base.copy(objective = Objective(prior = 0.9))
    def pooled(covering: Window): Double = Belief.from(
      eager.copy(responses = List(saysOf("ada", covering, Availability.Yes))),
    )(0, 0)
    assert(
      pooled(early) > pooled(june),
      s"${ pooled(early) } should exceed ${ pooled(
          june,
        ) }: a narrower window " + "is diluted less",
    )

  test("a pooled answer does not reach slots it fails to enclose"):
    // Encloses the 11th to the 13th, but only clips the 25th to the 27th.
    val partial = belief(saysOf(
      "ada",
      window("2027-06-10", "2027-06-26"),
      Availability.No,
    ))
    assertEquals(
      partial(0, 0),
      Availability.No.probability,
      "encloses this one",
    )
    assertEquals(
      partial(0, 1),
      base.objective.prior,
      "but only clips the other",
    )

    // Overlaps the 11th to the 13th without enclosing it, so says nothing.
    val straddling = belief(saysOf(
      "ada",
      window("2027-06-10", "2027-06-12"),
      Availability.No,
    ))
    assertEquals(straddling(0, 0), base.objective.prior)
