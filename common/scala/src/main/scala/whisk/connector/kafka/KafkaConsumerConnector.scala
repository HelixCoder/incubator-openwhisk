/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.connector.kafka

import java.util.Properties

import akka.actor.ActorSystem
import org.apache.kafka.clients.consumer.{ConsumerConfig, KafkaConsumer}
import org.apache.kafka.common.errors.{RetriableException, WakeupException}
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import pureconfig.loadConfigOrThrow
import whisk.common.Logging
import whisk.core.ConfigKeys
import whisk.core.connector.MessageConsumer

import scala.collection.JavaConversions.{iterableAsScalaIterable, seqAsJavaList}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

case class KafkaConsumerConfig(sessionTimeoutMs: Long)

class KafkaConsumerConnector(
  kafkahost: String,
  groupid: String,
  topic: String,
  override val maxPeek: Int = Int.MaxValue)(implicit logging: Logging, actorSystem: ActorSystem)
    extends MessageConsumer {

  implicit val ec: ExecutionContext = actorSystem.dispatcher
  private val gracefulWaitTime = 100.milliseconds

  // The consumer is generally configured via getProps. This configuration only loads values necessary for "outer"
  // logic, like the wakeup timer.
  private val cfg = loadConfigOrThrow[KafkaConsumerConfig](ConfigKeys.kafkaConsumer)

  /**
   * Long poll for messages. Method returns once message are available but no later than given
   * duration.
   *
   * @param duration the maximum duration for the long poll
   */
  override def peek(duration: FiniteDuration = 500.milliseconds,
                    retry: Int = 3): Iterable[(String, Int, Long, Array[Byte])] = {

    // poll can be infinitely blocked in edge-cases, so we need to wakeup explicitly.
    val wakeUpTask = actorSystem.scheduler.scheduleOnce(cfg.sessionTimeoutMs.milliseconds + 1.second)(consumer.wakeup())

    try {
      consumer.poll(duration.toMillis).map(r => (r.topic, r.partition, r.offset, r.value))
    } catch {
      // Happens if the peek hangs.
      case _: WakeupException if retry > 0 =>
        logging.error(this, s"poll timeout occurred. Retrying $retry more times.")
        Thread.sleep(gracefulWaitTime.toMillis) // using Thread.sleep is okay, since `poll` is blocking anyway
        peek(duration, retry - 1)
      case e: RetriableException if retry > 0 =>
        logging.error(this, s"$e: Retrying $retry more times")
        wakeUpTask.cancel()
        Thread.sleep(gracefulWaitTime.toMillis) // using Thread.sleep is okay, since `poll` is blocking anyway
        peek(duration, retry - 1)
      // Every other error results in a restart of the consumer
      case e: Throwable =>
        recreateConsumer()
        throw e
    } finally wakeUpTask.cancel()
  }

  /**
   * Commits offsets from last poll.
   */
  def commit(retry: Int = 3): Unit =
    try {
      consumer.commitSync()
    } catch {
      case e: RetriableException =>
        if (retry > 0) {
          logging.error(this, s"$e: retrying $retry more times")
          Thread.sleep(gracefulWaitTime.toMillis) // using Thread.sleep is okay, since `commitSync` is blocking anyway
          commit(retry - 1)
        } else {
          throw e
        }
    }

  override def close(): Unit = {
    consumer.close()
    logging.info(this, s"closing '$topic' consumer")
  }

  private def getProps: Properties = {
    val props = new Properties
    props.put(ConsumerConfig.GROUP_ID_CONFIG, groupid)
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkahost)
    props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPeek.toString)

    val config =
      KafkaConfiguration.configMapToKafkaConfig(loadConfigOrThrow[Map[String, String]](ConfigKeys.kafkaCommon)) ++
        KafkaConfiguration.configMapToKafkaConfig(loadConfigOrThrow[Map[String, String]](ConfigKeys.kafkaConsumer))
    config.foreach {
      case (key, value) => props.put(key, value)
    }
    props
  }

  /** Creates a new kafka consumer and subscribes to topic list if given. */
  private def getConsumer(props: Properties, topics: Option[List[String]] = None) = {
    val keyDeserializer = new ByteArrayDeserializer
    val valueDeserializer = new ByteArrayDeserializer
    val consumer = new KafkaConsumer(props, keyDeserializer, valueDeserializer)
    topics.foreach(consumer.subscribe(_))
    consumer
  }

  private def recreateConsumer(): Unit = {
    val oldConsumer = consumer
    Future {
      oldConsumer.close()
      logging.info(this, s"old consumer closed")
    }
    consumer = getConsumer(getProps, Some(List(topic)))
  }

  @volatile private var consumer = getConsumer(getProps, Some(List(topic)))
}
