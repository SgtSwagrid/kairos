package com.alecdorrington.common
package model

import com.alecdorrington.common.solve.Fixture.window
import munit.FunSuite

class WindowSuite extends FunSuite:

  test("length counts both endpoints, and of builds a window of that length"):
    assertEquals(
      window("2027-06-11", "2027-06-13").length,
      3,
    )
    assertEquals(
      window("2027-06-11", "2027-06-11").length,
      1,
    )
    val built = Window.of(Day.parse("2027-06-11").get, 3)
    assertEquals(built.end.iso, "2027-06-13")
    assertEquals(built.length, 3)

  test(
    "containment, enclosure and overlap are inclusive, overlap is symmetric, and enclosing finds the smallest covering window",
  ):
    val june  = window("2027-06-01", "2027-06-30")
    val spans = window("2027-06-29", "2027-07-02")
    assert(june.contains(Day.parse("2027-06-01").get))
    assert(june.contains(Day.parse("2027-06-30").get))
    assert(!june.contains(Day.parse("2027-07-01").get))
    assert(june.encloses(window("2027-06-11", "2027-06-13")))
    assert(!june.encloses(spans))
    assert(june.encloses(june))
    assert(june.overlaps(spans))
    assert(spans.overlaps(june))
    assert(!june.overlaps(window("2027-07-01", "2027-07-31")))
    val enclosing = Window
      .enclosing(List(
        window("2027-06-11", "2027-06-13"),
        window("2027-05-01", "2027-05-03"),
        window("2027-08-20", "2027-08-22"),
      ))
      .get
    assertEquals(enclosing.start.iso, "2027-05-01")
    assertEquals(enclosing.end.iso, "2027-08-22")
    assertEquals(Window.enclosing(List.empty), None)

  test(
    "months partition a span without gaps or overlaps, a whole one named rather than given as a range",
  ):
    val span   = window("2027-05-20", "2027-08-10")
    val months = Window.months(span)
    assertEquals(months.map(_.showBrief).size, 4)
    assertEquals(months.head.start.iso, "2027-05-20")
    assertEquals(months.last.end.iso, "2027-08-10")
    assertEquals(months.map(_.length).sum, span.length)
    months
      .sliding(2)
      .foreach:
        case List(earlier, later) => assertEquals(
            earlier.end.plus(1).epochDay,
            later.start.epochDay,
          )
        case _ => ()
    assertEquals(
      Window.months(window("2027-06-05", "2027-06-09")).map(_.show),
      List(window("2027-06-05", "2027-06-09").show),
    )
    assertEquals(
      window("2027-06-01", "2027-06-30").showBrief,
      "June 2027",
    )

  test("a slot across a month boundary still gets a pooled question"):
    // Otherwise it is never screened, keeps the prior while everything around it
    // is talked down, and rises to the top for want of anyone being asked.
    val straddling = Slot(
      id = Id("Hut@2027-07-30"),
      venue = "Hut",
      window = window("2027-07-30", "2027-08-01"),
      capacity = 40,
      cost = 0,
    )
    val within = Slot(
      id = Id("Hut@2027-07-09"),
      venue = "Hut",
      window = window("2027-07-09", "2027-07-11"),
      capacity = 40,
      cost = 0,
    )
    val pooled = Question
      .candidates(List(within, straddling))
      .collect:
        case Question.AboutWindow(covered) => covered

    assert(
      pooled.exists(_.encloses(straddling.window)),
      s"nothing among ${ pooled.map(_.show) } covers the straddling slot",
    )
    assert(pooled.exists(_.encloses(within.window)))
