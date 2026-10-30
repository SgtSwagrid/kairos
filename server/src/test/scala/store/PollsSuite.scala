package com.alecdorrington.server
package store

import cats.effect.IO
import cats.syntax.all.*
import com.alecdorrington.common.api.{Draft, ParticipantDraft, VenueDraft}
import com.alecdorrington.common.model.*
import io.circe.parser.decode
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.Comparator
import munit.CatsEffectSuite

class PollsSuite extends CatsEffectSuite:

  /** A directory of its own for each test, cleaned up afterwards. */
  private val scratch = FunFixture[Path](
    setup = _ => Files.createTempDirectory("kairos-polls"),
    teardown = directory =>
      Files
        .walk(directory)
        .sorted(Comparator.reverseOrder)
        .forEach(Files.deleteIfExists(_)),
  )

  private def draft(title: String): Draft = Draft(
    title = title,
    venues = List(VenueDraft(
      name = "Hut",
      openings = List(Window(Day.of(2027, 6, 1), Day.of(2027, 6, 30))),
      capacity = 20,
      cost = 0,
    )),
    participants = (1 to 300)
      .map(index => ParticipantDraft(s"guest$index"))
      .toList,
  )

  private def answered(poll: Poll, index: Int): Poll =
    poll.record(List(Response(
      poll.participants(index % poll.participants.size).id,
      Question.AboutSlot(poll.slots.head.id),
      Availability.Yes,
    )))

  scratch.test(
    "concurrent answers all survive, and the file stays readable, across a restart",
  ): directory =>
    val file = directory.resolve("polls.json")
    val poll = draft("Busy").toPoll(Id("busy"))

    val exercise =
      for
        store <- Polls.open(Some(file))
        _     <- store.put(poll)
        // Every guest answering at once, which for thirty-odd people is simply
        // what happens when a round goes out.
        _ <- (0 until 64)
          .toList
          .parTraverse(index =>
            store.update(Id[Poll]("busy"))(answered(_, index)),
          )
        held     <- store.get(Id("busy"))
        saved    <- IO.blocking(String(Files.readAllBytes(file), UTF_8))
        reopened <- Polls.open(Some(file))
        found    <- reopened.get(Id("busy"))
      yield (held, saved, found)

    exercise.map: (held, saved, found) =>
      assertEquals(
        held.map(_.responses.size),
        Some(64),
        "one per guest",
      )
      assert(
        decode[List[Poll]](saved).isRight,
        s"the file did not survive: ${ saved.take(80) }",
      )
      assertEquals(
        decode[List[Poll]](saved)
          .toOption
          .flatMap(_.headOption)
          .map(_.responses.size),
        Some(64),
      )
      assertEquals(found.map(_.title), Some("Busy"))
      assertEquals(found.map(_.responses.size), Some(64))

  scratch.test("an unreadable file is set aside rather than written over"):
    directory =>
      val file = directory.resolve("polls.json")

      val exercise =
        for
          _ <- IO.blocking(Files.write(
            file,
            "not json at all".getBytes(UTF_8),
          ))
          store <- Polls.open(Some(file))
          _     <- store.put(draft("Fresh").toPoll(Id("fresh")))
          kept  <-
            IO.blocking(Files.list(directory).toArray.map(_.toString).toList)
        yield kept

      exercise.map: kept =>
        assert(
          kept.exists(_.endsWith(".bad")),
          s"the unreadable file should have been kept: $kept",
        )

  scratch.test("polls are listed most recently created first"): directory =>
    val exercise =
      for
        store <- Polls.open(Some(directory.resolve("polls.json")))
        _     <- (1 to 8)
          .toList
          .traverse(index =>
            store.put(draft(s"Poll $index").toPoll(Id(s"p$index"))),
          )
        listed <- store.all
      yield listed.map(_.title)

    exercise.map: titles =>
      assertEquals(titles.head, "Poll 8")
      assertEquals(titles.last, "Poll 1")

  test("identifiers are unguessable"):
    val exercise =
      for
        polls        <- Tokens.several[Poll](64)
        participants <- Tokens.several[Participant](64)
      yield polls ++ participants.map(_.value).map(Id[Poll](_))

    exercise.map: made =>
      val values = made.map(_.value)
      assertEquals(
        values.distinct.size,
        values.size,
        "identifiers repeated",
      )
      assert(
        values.forall(_.lengthIs >= 32),
        s"too short to be unguessable: ${ values.take(2) }",
      )
      assert(
        values.forall(_.forall(character =>
          character.isDigit || ('a' to 'f').contains(character),
        )),
        "expected hexadecimal",
      )
