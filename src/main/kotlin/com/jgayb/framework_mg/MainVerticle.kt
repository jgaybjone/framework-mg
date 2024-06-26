package com.jgayb.framework_mg

import io.netty.handler.codec.mqtt.MqttQoS
import io.vertx.core.AbstractVerticle
import io.vertx.core.Handler
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.mqtt.MqttClient
import io.vertx.mqtt.MqttClientOptions
import java.io.File
import java.util.*

class MainVerticle : AbstractVerticle() {

  private var mqttClient: MqttClient? = null

  //remote_door_lock
  private val baseDir = System.getenv().getOrDefault("BASE_DIR", System.getenv()["PWD"])
  override fun start(startPromise: Promise<Void>) {
    val router = Router.router(vertx)
    router.route(HttpMethod.GET, "/framework/:framework")
      .handler(versionHandler)

    router.route(HttpMethod.GET, "/framework/:framework/:version")
      .handler(filesHandler)

    router.route(HttpMethod.GET, "/framework/:framework/:version/*")
      .handler(fileDownload)

    router.route(HttpMethod.GET, "/health")
      .handler {
        if (mqttClient == null || (mqttClient?.isConnected == false)) {
          println("MQTT client is not connected")
          it.response().setStatusCode(500).end()
        } else {
          it.response().setStatusCode(200).end()
        }
      }

    vertx
      .createHttpServer()
      .requestHandler(router)
      .listen(8888) { http ->
        if (http.succeeded()) {
          startPromise.complete()
          println("HTTP server started on port 8888")
        } else {
          startPromise.fail(http.cause());
        }
      }
    mqtt()
  }

  private var versionHandler: Handler<RoutingContext> = Handler {
    val response = it.response()
      .putHeader("Content-Type", "application/json")
    val respBody = JsonObject()
    respBody.put("latestVersion", "")
    val frameworkDir = File("$baseDir/${it.pathParam("framework")}")
    if (frameworkDir.exists()) {
      val files = frameworkDir.listFiles()?.filter { f -> f.isDirectory }
      if (files?.isNotEmpty() == true) {
        val first = files.max()
        respBody.put("latestVersion", first?.name)
        respBody.put("versions", files.map { f -> f.name })
      }
    }
    response
      .end(respBody.encode())
  }
  private var filesHandler: Handler<RoutingContext> = Handler {
    val response = it.response()
      .putHeader("Content-Type", "application/json")
    val respBody = JsonObject()
    val fileInfo = mutableListOf<String>()
    val fileDir = File("$baseDir/${it.pathParam("framework")}/${it.pathParam("version")}")
    val transform: (File) -> String = { f -> f.absolutePath.replace("${fileDir.absolutePath}/", "") };
    if (fileDir.exists()) {
      var subDirs = fileDir.listFiles()?.filter { f -> f.isDirectory }
      val files = fileDir.listFiles()?.filter { f -> f.isFile }
      files?.map(transform)?.let { it1 ->
        fileInfo.addAll(it1)
      }
      while (!subDirs.isNullOrEmpty()) {
        subDirs.map { sd ->
          sd.listFiles()?.filter { f -> f.isFile }
        }
          .filter { fs -> !fs.isNullOrEmpty() }
          .flatMap { fs -> fs!! }
          .map(transform)
          .let { fs -> fileInfo.addAll(fs) }

        subDirs = subDirs.map { sd ->
          sd.listFiles()?.filter { f -> f.isDirectory }
        }.filter { fs -> !fs.isNullOrEmpty() }
          .flatMap { fs -> fs!! }.toList()
      }
    }
    fileInfo.removeIf { f -> f.endsWith(".DS_Store") }
    respBody.put("files", fileInfo)
    response
      .end(respBody.encode())
  }

  private var fileDownload: Handler<RoutingContext> = Handler {
    val path = it.request().path()
    val file = File(path)
    val response = it.response()
    response
      .setStatusCode(200)
      .putHeader("Content-Type", "application/octet-stream")
      .putHeader("Content-Length", "${file.length()}")
      .putHeader("Content-Disposition", "attachment; filename=" + file.getName())
      .sendFile(file.absolutePath)
  }

  private fun mqtt() {
    val clientOptions = MqttClientOptions()
    clientOptions.clientId = UUID.randomUUID().toString().replace("-", "")
    clientOptions.username = "YzyMqttClient"
    clientOptions.password = "YzyMqttClient"
    clientOptions.isAutoKeepAlive = true
    clientOptions.isCleanSession = false
    clientOptions.keepAliveInterval = 60
    clientOptions.reconnectAttempts = 10
    clientOptions.reconnectInterval = 10000
    this.mqttClient = MqttClient.create(vertx, clientOptions)
    mqttClient?.connect(1883, "192.168.5.8")
    vertx.setPeriodic(3600000) {
      if (mqttClient?.isConnected == false) {
        mqttClient?.connect(1883, "192.168.5.8")
      }
      mqttClient?.publish(
        "homeassistant/switch/building/door_lock/config", Buffer.buffer(
          """
          {
            "unique_id": "building-door-lock-001",
            "name": "大楼门禁",
            "icon": "mdi:gesture-tap-button",
            "state_topic": "homeassistant/switch/building/door_lock/state",
            "command_topic": "homeassistant/switch/building/door_lock/set",
            "json_attributes_topic": "homeassistant/switch/building/door_lock/attributes",
            "device": {
              "identifiers": "door-lock-001",
              "manufacturer": "华为",
              "model": "LK",
              "name": "esp32",
              "sw_version": "1.0"
            }
          }
          """
        ),
        MqttQoS.AT_MOST_ONCE, false, false
      )
    }

  }
}
