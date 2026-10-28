package com.alecdorrington.common
package model

import munit.FunSuite

class DaySuite extends FunSuite:

  /** Reference dates, each with its offset from the epoch and its weekday. */
  private val references = List(
    ("1970-01-01", 0, "Thu"),
    ("1969-12-31", -1, "Wed"),
    ("2000-01-01", 10957, "Sat"),
    ("2000-02-29", 11016, "Tue"),
    ("2026-09-20", 20716, "Sun"),
    ("2027-06-11", 20980, "Fri"),
    ("2100-03-01", 47541, "Mon"),
  )

  test(
    "known dates have the expected offset from the epoch and weekday, rendering as ISO inverts parsing, and malformed dates are rejected",
  ):
    // The references fall on all seven weekdays, either side of the epoch.
    references.foreach: (iso, epochDay, weekday) =>
      assertEquals(
        Day.parse(iso).map(_.epochDay),
        Some(epochDay),
        iso,
      )
      assertEquals(
        Day.parse(iso).map(_.weekdayName),
        Some(weekday),
        iso,
      )
      assertEquals(Day.parse(iso).map(_.iso), Some(iso))
    assertEquals(Day.parse("not a date"), None)
    assertEquals(Day.parse("2027-06"), None)
    assertEquals(Day.parse("2027-xx-11"), None)

  test("civil components invert construction across a long span"):
    (-30000 to 30000).foreach: epochDay =>
      val day                       = Day.ofEpochDay(epochDay)
      val (year, month, dayOfMonth) = day.civil
      assertEquals(
        Day.of(year, month, dayOfMonth).epochDay,
        epochDay,
      )

  test(
    "month boundaries are found correctly, and 29 February exists only in leap years",
  ):
    val day = Day.of(2027, 6, 11)
    assertEquals(day.startOfMonth.iso, "2027-06-01")
    assertEquals(day.endOfMonth.iso, "2027-06-30")
    assertEquals(
      Day.of(2027, 2, 14).endOfMonth.iso,
      "2027-02-28",
    )
    assertEquals(
      Day.of(2028, 2, 14).endOfMonth.iso,
      "2028-02-29",
    )
    assertEquals(
      Day.of(2027, 12, 3).endOfMonth.iso,
      "2027-12-31",
    )
    assertEquals(Day.of(2024, 2, 29).iso, "2024-02-29")
    assertEquals(Day.of(2023, 2, 29).iso, "2023-03-01")
    assertEquals(Day.of(1900, 2, 29).iso, "1900-03-01")
    assertEquals(Day.of(2000, 2, 29).iso, "2000-02-29")
