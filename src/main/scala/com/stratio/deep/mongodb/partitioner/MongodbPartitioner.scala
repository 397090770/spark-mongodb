/*
 *  Licensed to STRATIO (C) under one or more contributor license agreements.
 *  See the NOTICE file distributed with this work for additional information
 *  regarding copyright ownership. The STRATIO (C) licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied. See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package com.stratio.deep.mongodb.partitioner

import com.mongodb.casbah.Imports._
import com.stratio.deep.DeepConfig
import com.stratio.deep.mongodb.{MongodbCredentials, MongodbConfig}
import com.stratio.deep.mongodb.partitioner.MongodbPartitioner._
import com.stratio.deep.partitioner.{DeepPartitionRange, DeepPartitioner}
import com.stratio.deep.util.using

import scala.util.Try

/**
 * @param config Partition configuration
 */
class MongodbPartitioner(
  config: DeepConfig) extends DeepPartitioner[MongodbPartition] {

  @transient private val hosts: List[ServerAddress] =
    config[List[String]](MongodbConfig.Host)
      .map(add => new ServerAddress(add))

  @transient private val credentials: List[MongoCredential] =
    config[List[MongodbCredentials]](MongodbConfig.Credentials).map{
      case MongodbCredentials(user,database,password) =>
        MongoCredential.createCredential(user,database,password)
    }

  private val databaseName: String = config(MongodbConfig.Database)

  private val collectionName: String = config(MongodbConfig.Collection)

  private val collectionFullName: String = s"$databaseName.$collectionName"

  override def computePartitions(): Array[MongodbPartition] =
    if (isShardedCollection)
      computeShardedChunkPartitions()
    else
      computeNotShardedPartitions()

  /**
   * @return Whether this is a sharded collection or not
   */
  protected def isShardedCollection: Boolean =
    using(MongoClient(hosts,credentials)) { mongoClient =>
      mongoClient.readPreference = ReadPreference.Nearest
      val collection = mongoClient(databaseName)(collectionName)
      collection.stats.ok && collection.stats.getBoolean("sharded", false)
    }

  /**
   * @return MongoDB partitions as sharded chunks.
   */
  protected def computeShardedChunkPartitions(): Array[MongodbPartition] =
    using(MongoClient(hosts,credentials)) { mongoClient =>
      mongoClient.readPreference = ReadPreference.Nearest

      Try {
        val chunksCollection = mongoClient(ConfigDatabase)(ChunksCollection)
        val dbCursor = chunksCollection.find(MongoDBObject("ns" -> collectionFullName))

        val shards = describeShardsMap()

        val partitions = dbCursor.zipWithIndex.map {
          case (chunk: DBObject, i: Int) =>
            val lowerBound = chunk.getAs[DBObject]("min")
            val upperBound = chunk.getAs[DBObject]("max")

            val hosts: Seq[String] = (for {
              shard <- chunk.getAs[String]("shard")
              hosts <- shards.get(shard)
            } yield hosts).getOrElse(Seq[String]())

            MongodbPartition(i,
              hosts,
              DeepPartitionRange(lowerBound, upperBound))
        }.toArray

        partitions

      }.recover {
        case _: Exception =>
          val serverAddressList: Seq[String] = mongoClient.allAddress.map {
            server => server.getHost + ":" + server.getPort
          }.toSeq
          Array(MongodbPartition(0, serverAddressList, DeepPartitionRange(None, None)))
      }.get
    }

  /**
   * @return Array of not-sharded MongoDB partitions.
   */
  protected def computeNotShardedPartitions(): Array[MongodbPartition] =
    using(MongoClient(hosts,credentials)) { mongoClient =>
      mongoClient.readPreference = ReadPreference.Nearest
      val ranges = splitRanges()

      val serverAddressList: Seq[String] = mongoClient.allAddress.map {
        server => server.getHost + ":" + server.getPort
      }.toSeq

      val partitions: Array[MongodbPartition] = ranges.zipWithIndex.map {
        case ((previous: Option[DBObject], current: Option[DBObject]), i) =>
          MongodbPartition(i,
            serverAddressList,
            DeepPartitionRange(previous, current))
      }.toArray

      partitions
    }

  /**
   * @return A sequence of minimum and maximum DBObject in range.
   */
  protected def splitRanges(): Seq[(Option[DBObject], Option[DBObject])] = {

    val cmd: MongoDBObject = MongoDBObject(
      "splitVector" -> collectionFullName,
      "keyPattern" -> MongoDBObject(MongodbConfig.SplitKey -> 1),
      "force" -> false,
      "maxChunkSize" -> config(MongodbConfig.SplitSize)
    )

    using(MongoClient(hosts,credentials)) { mongoClient =>
      mongoClient.readPreference = ReadPreference.Nearest
      Try {
        val data = mongoClient("admin").command(cmd)
        val splitKeys = data.as[List[DBObject]]("splitKeys").map(Option(_))
        val ranges = (None +: splitKeys) zip (splitKeys :+ None)
        ranges.toSeq
      }.recover {
        case _: Exception =>
          val stats = mongoClient(databaseName)(collectionName).stats
          val shards = mongoClient(ConfigDatabase)(ShardsCollection)
            .find(MongoDBObject("_id" -> stats.getString("primary")))

          val shard = shards.next()
          val shardHost: String = shard.as[String]("host")
            .replace(shard.get("_id") + "/", "")

          using(MongoClient(shardHost)) { shardClient =>
            val data = shardClient.getDB("admin").command(cmd)
            val splitKeys = data.as[List[DBObject]]("splitKeys").map(Option(_))
            val ranges = (None +: splitKeys) zip (splitKeys :+ None)
            ranges.toSeq
          }
      }.getOrElse(Seq((None, None)))
    }

  }

  /**
   * @return Map of shards.
   */
  protected def describeShardsMap(): Map[String, Seq[String]] =
    using(MongoClient(hosts,credentials)) { mongoClient =>
      mongoClient.readPreference = ReadPreference.Nearest
      val shardsCollection = mongoClient(ConfigDatabase)(ShardsCollection)

      val shards = shardsCollection.find().map { shard =>
        val hosts: Seq[String] = shard.getAs[String]("host")
          .fold(ifEmpty = Seq[String]())(_.split(",").map(_.split("/").reverse.head).toSeq)
        (shard.as[String]("_id"), hosts)
      }.toMap

      shards
    }

}

object MongodbPartitioner {

  val ConfigDatabase = "config"
  val ChunksCollection = "chunks"
  val ShardsCollection = "shards"

}
