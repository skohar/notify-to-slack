package com.github.skohar.notifytoslack

import cats.std.all._
import cats.syntax.traverse._
import com.amazonaws.services.lambda.AWSLambdaClient
import com.amazonaws.services.lambda.model.GetFunctionRequest
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.events.SNSEvent
import com.amazonaws.services.lambda.runtime.events.SNSEvent.SNS
import net.gpedro.integrations.slack.{SlackApi, SlackMessage}
import org.apache.commons.lang3.exception.ExceptionUtils
import play.api.libs.json.Json

import scala.collection.JavaConversions._
import scala.util.control.Exception._

class App {

  def toMessage(sns: SNS): Either[Throwable, Message] = try {
    Right(Json.parse(sns.getMessage).as[Message])
  } catch {
    case e: Throwable => Left(e)
  }

  def toTextForSlack(message: Message) =
    s"""AutoScalingGroupName: ${message.AutoScalingGroupName}
        |Description: ${message.Description}""".stripMargin

  def post(config: LambdaConfig, slackMessage: SlackMessage) = new SlackApi(config.slackWebHookUrl).call(slackMessage)

  def handler(event: SNSEvent, context: Context): Any = {
    val result: Either[Throwable, String] = for {
      config <- App.description2config(context).right
      messages <- (event.getRecords.map(_.getSNS).map(toMessage).toList: List[Either[Throwable, Message]]).sequenceU
        .right
      voids <- (messages.map(toTextForSlack).map(new SlackMessage("AutoScalingGroup", _))
        .map(x => allCatch either new MySlackApi(config.slackWebHookUrl).send(x.prepare())): List[Either[Throwable, String]])
        .sequenceU.right
    } yield {
      voids.mkString(System.lineSeparator)
    }
    result.left.map(ExceptionUtils.getStackTrace).merge
  }
}

object App {

  def log(config: LambdaConfig, text: String) =
    new SlackApi(config.slackWebHookUrl).call(new SlackMessage("lambda:debug", s"``` $text ```"))

  def description2config(context: Context): Either[Throwable, LambdaConfig] = allCatch either {
    val request = new GetFunctionRequest().withFunctionName(context.getFunctionName)
    val description = new AWSLambdaClient().getFunction(request).getConfiguration.getDescription
    Json.parse(description).as[LambdaConfig]
  }
}
