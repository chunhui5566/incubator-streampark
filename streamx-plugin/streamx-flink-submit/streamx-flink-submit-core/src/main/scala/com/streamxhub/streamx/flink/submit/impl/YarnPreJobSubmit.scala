/*
 * Copyright (c) 2019 The StreamX Project
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.streamxhub.streamx.flink.submit.impl

import com.streamxhub.streamx.common.enums.DevelopmentMode
import com.streamxhub.streamx.common.util.{FlinkUtils, Utils}
import com.streamxhub.streamx.flink.packer.pipeline.ShadedBuildResponse
import com.streamxhub.streamx.flink.submit.`trait`.YarnSubmitTrait
import com.streamxhub.streamx.flink.submit.bean._
import org.apache.flink.client.deployment.DefaultClusterClientServiceLoader
import org.apache.flink.client.program.{ClusterClient, PackagedProgram}
import org.apache.flink.configuration.{Configuration, DeploymentOptions}
import org.apache.flink.yarn.YarnClusterDescriptor
import org.apache.flink.yarn.configuration.YarnDeploymentTarget
import org.apache.flink.yarn.entrypoint.YarnJobClusterEntrypoint
import org.apache.hadoop.fs.{Path => HadoopPath}
import org.apache.hadoop.yarn.api.records.ApplicationId

import java.io.File
import scala.collection.JavaConversions._

/**
 * yarn PreJob mode submit
 */
@deprecated
object YarnPreJobSubmit extends YarnSubmitTrait {

  override def setConfig(submitRequest: SubmitRequest, flinkConfig: Configuration): Unit = {
    //execution.target
    flinkConfig.set(DeploymentOptions.TARGET, YarnDeploymentTarget.PER_JOB.getName)
    logInfo(
      s"""
         |------------------------------------------------------------------
         |Effective submit configuration: $flinkConfig
         |------------------------------------------------------------------
         |""".stripMargin)
  }

  override def doSubmit(submitRequest: SubmitRequest, flinkConfig: Configuration): SubmitResponse = {

    val flinkHome = submitRequest.flinkVersion.flinkHome

    val clusterClientServiceLoader = new DefaultClusterClientServiceLoader
    val clientFactory = clusterClientServiceLoader.getClusterClientFactory[ApplicationId](flinkConfig)
    var packagedProgram: PackagedProgram = null
    var clusterClient: ClusterClient[ApplicationId] = null

    val clusterDescriptor = {
      val clusterDescriptor = clientFactory.createClusterDescriptor(flinkConfig).asInstanceOf[YarnClusterDescriptor]
      val flinkDistJar = FlinkUtils.getFlinkDistJar(flinkHome)
      clusterDescriptor.setLocalJarPath(new HadoopPath(flinkDistJar))
      clusterDescriptor.addShipFiles(List(new File(s"$flinkHome/lib")))
      clusterDescriptor
    }

    try {
      clusterClient = {
        val clusterSpecification = clientFactory.getClusterSpecification(flinkConfig)
        logInfo(
          s"""
             |------------------------<<specification>>-------------------------
             |$clusterSpecification
             |------------------------------------------------------------------
             |""".stripMargin)

        val jarFile = submitRequest.developmentMode match {
          case DevelopmentMode.FLINKSQL =>
            submitRequest.checkBuildResult()
            val buildResult = submitRequest.buildResult.asInstanceOf[ShadedBuildResponse]
            new File(buildResult.shadedJarPath)
          case _ => new File(submitRequest.flinkUserJar)
        }

        val packageProgramJobGraph = super.getJobGraph(flinkConfig, submitRequest, jarFile)
        packagedProgram = packageProgramJobGraph._1
        val jobGraph = packageProgramJobGraph._2

        logInfo(
          s"""
             |-------------------------<<applicationId>>------------------------
             |jobGraph getJobID: ${jobGraph.getJobID.toString}
             |__________________________________________________________________
             |""".stripMargin)
        deployInternal(
          clusterDescriptor,
          clusterSpecification,
          submitRequest.effectiveAppName,
          classOf[YarnJobClusterEntrypoint].getName,
          jobGraph,
          false
        ).getClusterClient

      }
      val applicationId = clusterClient.getClusterId
      logInfo(
        s"""
           |-------------------------<<applicationId>>------------------------
           |Flink Job Started: applicationId: $applicationId
           |__________________________________________________________________
           |""".stripMargin)

      SubmitResponse(applicationId.toString, flinkConfig.toMap)
    } finally {
      if (submitRequest.safePackageProgram) {
        Utils.close(packagedProgram)
      }
      Utils.close(clusterClient, clusterDescriptor)
    }
  }

}
