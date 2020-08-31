package com.dyptan

import java.util.Properties
import java.util.logging.Logger

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Directives.{path, _}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import spray.json._

import scala.concurrent.duration._

import scala.io.Source

object TrainerActor {
  case class TrainRequest(trainingDataSetPath: String, limit: Int, iterations: Int)
}

class TrainerActor extends Actor with ActorLogging {
  import TrainerActor._
  val trainer = new Trainer

  override def receive(): Receive = {
    case TrainRequest(path, limit, iterations) =>
      log.info(s"Train request received with path: $path, limit: $limit,iterations: $iterations")

      trainer.setSource(new java.net.URL(path), limit, iterations)
      trainer.train()
      log.info(s"Training completed")
      trainer.save()
      log.info(s"New model saved to "+TrainerGateway.properties.getOrDefault("model.path", "/tmp/trainedmodel"))

  }
}

trait TrainRequestJsonProtocol extends DefaultJsonProtocol {
  import TrainerActor._
  implicit val requestFormat = jsonFormat3(TrainRequest)
}

object TrainerGateway {

  val log = Logger.getLogger(this.getClass.getName)
  val properties = new Properties()
  val source = Source.fromFile("conf/application.properties")
  properties.load(source.bufferedReader())

  def main(args: Array[String]): Unit = {

    implicit val system = ActorSystem("HighLevelExample")
    implicit val materializer = ActorMaterializer()
    import TrainerActor._
    import system.dispatcher
    implicit val defaultTimeout = Timeout(300 seconds)

    val trainerActor = system.actorOf(Props[TrainerActor], "TRainerActor")

    log.info("Starting actorSYStem")

    val trainerServerRoute =
      path("api" / "trainer") {
        parameters('path.as[String],
          'iterations.as[Int],
          'limit.as[Int]) { (path, limit, iterations) =>
          post {
            val trainerResponseFuture = (trainerActor ? TrainRequest(path, limit, iterations))
              .mapTo[String]

            val entityFuture = trainerResponseFuture.map { responseOption =>
              HttpEntity(
                ContentTypes.`text/plain(UTF-8)`,
                responseOption
              )
            }
            complete(entityFuture)
          }
        }
      }

    val port = properties.getOrDefault("gateway.port", "8081").asInstanceOf[String].toInt

    Http().bindAndHandle(trainerServerRoute, "0.0.0.0", port)
    log.info("Trainer Gateway started to listen on port " + port)
  }
}