package com.alecdorrington.server
package services

import com.alecdorrington.server.api.CoreApi
import com.alecdorrington.server.config.Env
import com.alecdorrington.server.html.Template
import sttp.tapir.*

/**
  * The implementation of the API endpoints specified in [[CoreApi]]. These are
  * general endpoints which are used across the entire application.
  */
object CoreService extends Service("core"):

  import Service.Endpoint

  /**
    * An endpoint that serves static files from the client's "resources"
    * directory. Returns `304 Not Modified` if the client's cached ETag matches.
    */
  lazy val assets: Endpoint = CoreApi.assetService.serverEndpoint

  /**
    * An endpoint that establishes a websocket connection so that the client is
    * able to detect when the server has been restarted, at which time the
    * client will proceed by reloading the page.
    *
    * @note
    *   Only available in development mode.
    */
  lazy val hotReload: Endpoint = CoreApi
    .hotReload
    .serverLogicSuccessPure: _ =>
      in => in.map(_ => "ok")

  /**
    * A health check endpoint that confirms the server is running and able to
    * accept requests.
    */
  lazy val health: Endpoint = CoreApi
    .health
    .serverLogicSuccessPure: _ =>
      "OK"

  /** Serves the index page of the website. */
  lazy val index: Endpoint = CoreApi
    .index
    .serverLogicSuccessPure: _ =>
      Template("IndexView")

  /**
    * Serves the organiser's page for one poll. The poll's identifier is read
    * from the address by the client, so every poll is served the same page.
    */
  lazy val organiser: Endpoint = CoreApi
    .organiser
    .serverLogicSuccessPure: _ =>
      Template("OrganiserView")

  /** Serves the page on which one participant answers their questions. */
  lazy val respond: Endpoint = CoreApi
    .respond
    .serverLogicSuccessPure: _ =>
      Template("RespondView")

  override lazy val api: List[Endpoint] =
    List(assets, health, index, organiser, respond) ++
      Option.when(Env.DEV_MODE)(hotReload)
