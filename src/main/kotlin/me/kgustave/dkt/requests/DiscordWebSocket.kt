/*
 * Copyright 2018 Kaidan Gustave
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:Suppress("LiftReturnOrAssignment", "MemberVisibilityCanPrivate", "MemberVisibilityCanBePrivate")
package me.kgustave.dkt.requests

import com.neovisionaries.ws.client.*
import kotlinx.coroutines.experimental.*
import me.kgustave.dkt.API
import me.kgustave.dkt.Discord
import me.kgustave.dkt.entities.impl.APIImpl
import me.kgustave.dkt.entities.impl.PresenceImpl
import me.kgustave.dkt.exceptions.DiscordConnectionException
import me.kgustave.dkt.events.DisconnectEvent
import me.kgustave.dkt.events.ReadyEvent
import me.kgustave.dkt.events.ShutdownEvent
import me.kgustave.dkt.handlers.SessionManager
import me.kgustave.dkt.handlers.event.EventHandler
import me.kgustave.dkt.handlers.event.GuildMembersChunkHandler
import me.kgustave.dkt.util.queue.RawEventQueue
import me.kgustave.dkt.util.createLogger
import me.kgustave.dkt.util.currentTime
import me.kgustave.dkt.util.snowflake
import me.kgustave.json.JSArray
import me.kgustave.json.JSObject
import me.kgustave.json.exceptions.JSException
import me.kgustave.json.jsonObject
import me.kgustave.json.parseJsonObject
import java.util.*
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * A full control WebSocket entity for the Discord gateway.
 *
 * @since  1.0.0
 * @author Kaidan Gustave
 */
class DiscordWebSocket
constructor(val api: APIImpl, private val sessionManager: SessionManager): WebSocketListener by WebSocketAdapter() {
    companion object {
        private val LOG = createLogger(DiscordWebSocket::class)
        const val IDENTIFY_DELAY = 5 // Seconds
    }

    @Volatile private lateinit var rateLimit: Job
    @Volatile private lateinit var life: Job
    @Volatile private var connected = false
    @Volatile private var ratelimitReset = 0L
    @Volatile private var messagesSent = 0
    @Volatile private var hasCreatedRatelimitError = false
    @Volatile private var shutdown = false

    private val rateLimitContext by lazy { newSingleThreadContext("WS-RateLimit Context") }
    private val lifeContext by lazy { newSingleThreadContext("WS-Heartbeat Context") }
    private val token = "Bot ${api.token}"
    private val eventsToReplay = RawEventQueue()
    private val rateLimitQueue = LinkedList<String>()
    private val chunkQueue = LinkedList<String>()

    private var sessionId: String? = null
    private var initiating = false
    private var ratelimitIdentify = false
    private var sentIdentify = false
    private var systemsReady = false
    private var previouslyConnected = false
    private var heartbeatStart = -1L
    private var reconnectDelay = 2

    @Volatile var chunkingGuildMembers = false

    var shouldReconnect = api.shouldAutoReconnect

    lateinit var websocket: WebSocket
        private set

    val handlers: Map<EventHandler.Type, EventHandler> = EventHandler.newEventHandlerMap(api)
    val traces: MutableSet<String> = HashSet()
    val rays: MutableSet<String> = HashSet()
    val ready: Boolean get() = !initiating

    init {
        startRateLimit()
        connect()
    }

    fun ready() {
        if(initiating) {
            initiating = false
            systemsReady = false
            if(!previouslyConnected) {
                previouslyConnected = true
                LOG.info("Finished Connecting!")
                api.signalReady()
                api.dispatchEvent(ReadyEvent(api, api.responses))
            } else {
                LOG.info("Finished Reloading!")
            }
        } else {
            LOG.info("Finished Resuming Session!")
        }

        api.status = API.Status.CONNECTED

        LOG.debug("Replaying ${eventsToReplay.size} cached events...")

        // Replay missed events
        var json = eventsToReplay.poll()
        while(json !== null) {
            dispatch(json)
            json = eventsToReplay.poll()
        }

        LOG.debug("Finished replaying cached events!")
    }

    fun connect() {
        if(api.status != API.Status.ATTEMPTING_TO_RECONNECT)
            api.status = API.Status.CONNECTING_TO_WEBSOCKET

        if(shutdown)
            throw RejectedExecutionException("Cannot connect after shutdown!")

        initiating = true

        try {
            // Create and initialize the websocket
            websocket = WebSocketFactory()
                .createSocket(api.gatewayUrl)
                .addListener(this) // Add this as a listener
                .connect()
        } catch(e: Exception) {
            throw DiscordConnectionException("Totally failed to connect to WebSocket!", e)
        }
    }

    fun sendMessage(message: String) = rateLimitQueue.addLast(message)

    fun reconnectViaManager() {
        if(!ratelimitIdentify) {
            LOG.warn("WebSocket experienced a disconnect (Possibly due to poor connection)! " +
                     "Adding session to reconnect queue!")
        }

        try {
            sessionManager.add(this)
        } catch(e: IllegalAccessException) {
            LOG.error("SessionManager rejected the session when attempting to add it to the queue!")
        }
    }

    fun reconnect(useSessionManager: Boolean = false, handleIdentify: Boolean = true) {
        if(shutdown) {
            api.status = API.Status.SHUTDOWN
            return api.dispatchEvent(ShutdownEvent(api, 1000))
        }

        if(!ratelimitIdentify) {
            val shardInfo = api.shardInfo
            if(useSessionManager && shardInfo !== null)
                LOG.warn("Session Manager now attempting to reconnect a shard: ${shardInfo.shardString}")
            else
                LOG.warn("WebSocket experienced a disconnect (Possibly due to poor connection)!")
            LOG.warn("Reconnecting in $reconnectDelay seconds...")
        }

        while(shouldReconnect) {
            api.status = API.Status.WAITING_TO_RECONNECT
            if(ratelimitIdentify && handleIdentify) {
                LOG.error("Encountered IDENTIFY (OP ${OpCode.IDENTIFY}) RateLimit! Waiting " +
                          "$IDENTIFY_DELAY seconds before trying again!")
                Thread.sleep(IDENTIFY_DELAY * 1000L)
            } else {
                Thread.sleep(reconnectDelay * 1000L)
            }
            api.status = API.Status.ATTEMPTING_TO_RECONNECT

            ratelimitIdentify = false

            LOG.warn("Attempting to reconnect!")

            try {
                connect()
                break
            } catch(e: RejectedExecutionException) {
                // This could occur if we had an issue shutting down.
                // In this case, we shutdown now.
                api.status = API.Status.SHUTDOWN
                return api.dispatchEvent(ShutdownEvent(api, 1000))
            } catch(e: RuntimeException) {
                reconnectDelay = min(reconnectDelay shl 1, 900)
                LOG.warn("Failed to reconnect! Retrying in $reconnectDelay")
            }
        }
    }

    fun shutdown() {
        shutdown = true
        shouldReconnect = false

        sessionManager.remove(this) // Remove this if we are in a queue

        close(reason = "Shutting Down")
    }

    fun close(code: Int = 1000, reason: String? = null) {
        if(reason !== null)
            websocket.sendClose(code, reason)
        else
            websocket.sendClose(code)

        if(!shouldReconnect) {
            val cancellation = CancellationException("Close Code $code${reason?.let { " - $it" } ?: ""}")
            lifeContext.cancel(cancellation)
            rateLimitContext.cancel(cancellation)
        }
    }

    fun updateTraces(json: JSArray, type: String, op: Int) {
        LOG.debug("Received a _trace for $type (OP $op) with $json")
        traces.clear()
        json.forEach { it?.let { traces.add("$it") } }
    }

    fun handle(events: Collection<JSObject>) = events.forEach(::dispatch)

    fun queueChunkRequest(json: JSObject) {
        chunkQueue.addLast("$json")
    }

    @Throws(Exception::class)
    override fun onConnected(websocket: WebSocket, headers: Map<String, List<String>>) {
        api.status = API.Status.IDENTIFYING
        LOG.info("Connected to WebSocket!")

        headers["cf-ray"]?.let {
            if(it.isNotEmpty()) {
                val ray = it[0]
                rays += ray
                LOG.debug("Received CloudFlare Ray: $ray")
            }
        }

        connected = true
        reconnectDelay = 2
        messagesSent = 0
        ratelimitReset = currentTime + 60000

        // null sessionId means we haven't sent IDENTIFY yet, or that our session has been invalidated
        if(sessionId === null) identify() else resume()
    }

    @Throws(Exception::class)
    override fun onDisconnected(websocket: WebSocket, serverCloseFrame: WebSocketFrame?,
                                clientCloseFrame: WebSocketFrame?, closedByServer: Boolean) {
        sentIdentify = false
        connected = false
        api.status = API.Status.DISCONNECTED

        var closeCode: CloseCode? = null
        val rawCode: Int

        life.cancel()

        if(serverCloseFrame !== null) {
            rawCode = serverCloseFrame.closeCode
            closeCode = CloseCode.of(rawCode)

            when {
                closeCode == CloseCode.RATE_LIMITED -> LOG.error("Websocket closed because you were rate-limited! " +
                                                                 "Sent 120 messages in less than a minute!")
                closeCode !== null -> LOG.debug("WebSocket connection closed with code $closeCode")
                else -> LOG.warn("WebSocket connection closed with unknown meaning for close-code $rawCode!")
            }
        } else {
            rawCode = 1000
        }

        val isInvalid = clientCloseFrame !== null &&
                        clientCloseFrame.closeCode == 1000 &&
                        clientCloseFrame.closeReason == "INVALIDATE_SESSION"

        // Close code is not null, and isReconnect is true
        // closeCode != null && closeCode.isReconnect
        val closeImpliesReconnect = closeCode?.isReconnect == true

        if(!shouldReconnect || !closeImpliesReconnect) { // Do not reconnect
            if(::rateLimit.isInitialized) {
                val cancellation = CancellationException("Close Code $rawCode${closeCode?.message?.let { " - $it" } ?: ""}")
                lifeContext.cancel(cancellation)
                rateLimitContext.cancel(cancellation)
            }

            if(!closeImpliesReconnect) {
                LOG.error("WebSocket was closed and cannot be recovered due to identification issues! $closeCode")
            }

            api.status = API.Status.SHUTDOWN
            api.dispatchEvent(ShutdownEvent(api, rawCode))
        } else {
            if(isInvalid) {
                // We dropped our session, we must
                // invalidate and send a RESUME
                invalidate()
            }
            api.dispatchEvent(DisconnectEvent(api, serverCloseFrame, clientCloseFrame, closedByServer))
            if(sessionId === null)
                reconnectViaManager()
            else
                reconnect()
        }
    }

    @Throws(Exception::class)
    override fun onTextMessage(websocket: WebSocket, text: String) {
        LOG.trace("Received WS Message: $text")
        val json = parseJsonObject(text)
        val op = json.int("op")

        val res = json.opt<Long>("s")
        // Response total
        if(res !== null) {
            api.responses = res
        }

        when(op) {
            OpCode.DISPATCH -> dispatch(json)

            OpCode.HEARTBEAT -> {
                LOG.debug("Received HEARTBEAT (OP 1). Sending response...")
                sendHeartbeat()
            }

            OpCode.RECONNECT -> {
                LOG.debug("Received RECONNECT (OP 7). Will now close connection...")
                close(4000, "OP 7: RECONNECT")
            }

            OpCode.INVALID_SESSION -> {
                LOG.debug("Received INVALID_SESSION (OP 9). Now invalidating...")
                sentIdentify = false
                val shouldResume = json["d"] as Boolean
                val closeCode = if(shouldResume) 4000 else 1000

                if(shouldResume) {
                    LOG.debug("Session can be resumed. Closing (Code: $closeCode), will send RESUME.")
                } else {
                    LOG.debug("Session cannot be resumed. Closing (Code: $closeCode).")
                    invalidate()
                }

                close(closeCode, "INVALIDATE_SESSION")
            }

            OpCode.HELLO -> {
                LOG.debug("Received HELLO (OP 10): $json")

                val d = json.obj("d")
                startLife(d["heartbeat_interval"].toString().toLong())

                if("_trace" in d) {
                    updateTraces(d.array("_trace"), "HELLO", OpCode.HELLO)
                }
            }

            OpCode.HEARTBEAT_ACK -> {
                LOG.trace("Received HEARTBEAT_ACK (OP 11)")
                api.ping = currentTime - heartbeatStart
            }

            // We opt 'd' here because if we get an OP that is unknown,
            // we have absolutely no idea what it might contain.
            else -> LOG.warn("Got an unknown OP Code ($op):\n${json.opt<Any>("d")}")
        }
    }

    @Throws(Exception::class)
    override fun onThreadCreated(websocket: WebSocket, threadType: ThreadType, thread: Thread) {
        thread.name = when(threadType) {
            ThreadType.CONNECT_THREAD -> "WS-Connect Thread"
            ThreadType.FINISH_THREAD  -> "WS-Finish Thread"
            ThreadType.READING_THREAD -> "WS-Reading Thread"
            ThreadType.WRITING_THREAD -> "WS-Writing Thread"
        }
    }

    @Throws(Exception::class)
    override fun onUnexpectedError(websocket: WebSocket, cause: WebSocketException) {
        handleCallbackError(websocket, cause)
    }

    override fun handleCallbackError(websocket: WebSocket, cause: Throwable) {
        LOG.error("A WebSocket error occurred!", cause)
    }

    private fun startRateLimit() {
        rateLimit = launch(rateLimitContext, start = CoroutineStart.LAZY) {
            var needRatelimit: Boolean
            var attemptedToSend: Boolean

            while(!rateLimit.isCancelled) {
                try {
                    // Wait until sending identify
                    if(!sentIdentify) {
                        delay(500)
                        continue
                    }

                    needRatelimit = false
                    attemptedToSend = false

                    val chunkRequest = chunkQueue.peekFirst()
                    if(chunkRequest !== null) {
                        needRatelimit = !sendText(chunkRequest, false)
                        if(!needRatelimit)
                            chunkQueue.removeFirst()

                        attemptedToSend = true
                    } else {
                        val message = rateLimitQueue.peekFirst()
                        if(message !== null) {
                            needRatelimit = !sendText(message, false)
                            if(!needRatelimit)
                                rateLimitQueue.removeFirst()
                            attemptedToSend = true
                        }
                    }

                    if(needRatelimit || !attemptedToSend) {
                        delay(1000)
                    }
                    Thread.sleep(1)
                } catch(e: CancellationException) {
                    // If we are interrupted this more than likely mean that the WebSocket disconnected mid send.
                    LOG.debug("WebSocket sending thread experienced an interruption. " +
                              "This is most likely the API disconnecting from the WebSocket.")
                    break
                }
            }
        }

        rateLimit.invokeOnCompletion { e ->
            e?.let { handleCallbackError(websocket, e) }
            if(shouldReconnect) {
                startRateLimit()
            }
        }

        rateLimit.start()
    }

    private fun startLife(timeout: Long) {
        life = launch(lifeContext, start = CoroutineStart.LAZY) {
            LOG.debug("Starting heartbeat at interval: ${timeout}ms")
            while(connected) {
                try {
                    sendHeartbeat()
                    delay(timeout, TimeUnit.MILLISECONDS)
                } catch(e: Exception) {
                    LOG.debug("Heartbeat thread was interrupted")
                    break
                }
            }
        }

        life.invokeOnCompletion(onCancelling = true) {
            if(it !== null) {
                handleCallbackError(websocket, it)
                startLife(timeout)
            }
        }

        life.start()
    }

    private fun invalidate() {
        // Empty sessionId means we haven't sent IDENTIFY yet, or that our session has been invalidated
        sessionId = null

        // We are no longer chunking guilds
        chunkingGuildMembers = false

        // IDENTIFY must be resent since our session is now invalid
        sentIdentify = false

        // Clear the current Chunking Members queue
        chunkQueue.clear()

        // Clear our entity caches since we will need to
        // re-populate them when we get a proper RESUME
        api.userMap.clear()
        api.guildMap.clear()
        api.textChannelMap.clear()
        api.voiceChannelMap.clear()
        api.categoryMap.clear()
        api.privateChannelMap.clear()

        // Clear the GuildQueue and EventCaches
        api.guildQueue.clear()
        api.eventCache.clear()
    }

    private fun dispatch(json: JSObject) {
        var type = json.string("t").toUpperCase() // 't' should never be null if it's DISPATCH

        if(type == "GUILD_MEMBER_ADD") {
            val gmcHandler = handlers[EventHandler.Type.GUILD_MEMBERS_CHUNK] as GuildMembersChunkHandler
            gmcHandler.addExpectedGuildMembers(snowflake(json.obj("d")["guild_id"]), 1)
        }

        if(type == "GUILD_MEMBER_REMOVE") {
            val gmcHandler = handlers[EventHandler.Type.GUILD_MEMBERS_CHUNK] as GuildMembersChunkHandler
            gmcHandler.addExpectedGuildMembers(snowflake(json.obj("d")["guild_id"]), -1)
        }

        // TODO Startup Guild and Member Chunks
        // TODO Hold off on other dispatched events

        if(initiating && !(type == "READY" ||
                           type == "GUILD_MEMBERS_CHUNK" ||
                           type == "RESUMED" ||
                           type == "GUILD_SYNC" ||
                           (!chunkingGuildMembers && type == "GUILD_CREATE"))) {

            val data = json.obj("d")
            if(chunkingGuildMembers && type == "GUILD_DELETE" && "unavailable" in data && data["unavailable"] as Boolean) {
                // We convert these to GUILD_CREATE
                type = "GUILD_CREATE"
                json["t"] = "GUILD_CREATE"
            } else {
                LOG.debug("Caching $type event to replay...")
                eventsToReplay.offer(json)
                return
            }
        }

        if(type == "PRESENCES_REPLACE") {
            // TODO Handle PRESENCES_REPLACE
            return
        }

        val data = json.obj("d")

        LOG.trace("> $type: $json")

        try {
            val t = type.toUpperCase()
            when(t) {
                "READY" -> {
                    api.status = API.Status.SETTING_UP
                    sessionId = data.string("session_id") // Grab session ID here
                    systemsReady = true
                    ratelimitIdentify = false

                    LOG.debug("Got READY event (Session ID: $sessionId)")

                    if("_trace" in data) {
                        updateTraces(data.array("_trace"), json.string("t"), json.int("op"))
                    }

                    handlers[EventHandler.Type.READY]!!.handle(json)
                }

                "RESUMED" -> {
                    sentIdentify = true
                    if(!systemsReady) {
                        api.status = API.Status.SETTING_UP
                        initiating = false
                        ready()
                    }

                    if("_trace" in data) {
                        updateTraces(data.array("_trace"), json.string("t"), json.int("op"))
                    }
                }

                else -> {
                    val eventHandlerType = EventHandler.Type.of(t)
                    if(eventHandlerType !== null && eventHandlerType in handlers) {
                        handlers[eventHandlerType]!!.handle(json)
                    } else {
                        LOG.debug("Could not find handler for dispatch type: $eventHandlerType -> $json")
                    }
                }
            }
        } catch(e: JSException) {
            LOG.warn("Encountered an internal websocket error parsing a JSON entity! Please " +
                     "report this to the developers of this library:\n{}\n-> {}: {}", e.message, type, json, e)
        } catch(e: Throwable) {
            LOG.error("Encountered an internal websocket error! Please report this to the developers of this " +
                      "library:\n-> {}: {}", type, json, e)
        }
    }

    private fun identify() {
        val packet = jsonObject {
            this["op"] = OpCode.IDENTIFY
            this["d"] = jsonObject d@ {
                this@d["v"] = Discord.KtInfo.GATEWAY_VERSION
                this@d["large_threshold"] = 250
                this@d["compressed"] = true
                this@d["presence"] = (api.presence as PresenceImpl).json
                this@d["token"] = token
                this@d["properties"] = jsonObject properties@ {
                    this@properties["\$os"] = System.getProperty("os.name")
                    this@properties["\$browser"] = "Kotlincord"
                    this@properties["\$device"] = "Kotlincord"
                }
            }
        }

        sendText(packet.toString(), false)
        sentIdentify = true
        ratelimitIdentify = true

        // Now we wait...
        api.status = API.Status.AWAITING_IDENTIFY_CONFIRMATION
    }

    private fun resume() {
        LOG.debug("Sending RESUME...")
        val packet = jsonObject {
            this["op"] = OpCode.RESUME
            this["d"] = jsonObject d@ {
                this@d["session_id"] = requireNotNull(sessionId) {
                    "Somehow, someway, the session ID provided when sending a resume request was null?"
                }
                this@d["token"] = token
                this@d["seq"] = api.responses
            }
        }

        sendText(packet.toString(), true)

        // Now we wait...
        api.status = API.Status.AWAITING_IDENTIFY_CONFIRMATION
    }

    private fun sendText(message: String, shouldRatelimit: Boolean): Boolean {
        if(!connected)
            return false

        val now = currentTime

        if(ratelimitReset <= now) {
            messagesSent = 0
            ratelimitReset = now + 60000
            hasCreatedRatelimitError = false
        }

        if(messagesSent <= 115 || (!shouldRatelimit && messagesSent < 120)) {
            // Took a tip from JDA's WebSocketClient and hold off at 119, even if I can go to 120
            LOG.trace("< $message")
            websocket.sendText(message)
            messagesSent++
            return true
        } else {
            if(!hasCreatedRatelimitError) {
                LOG.warn("You just hit a WebSocket RateLimit! If you see this a lot, " +
                         "contact the developers of this library!")
                hasCreatedRatelimitError = true
            }
            return false
        }
    }

    private fun sendHeartbeat() {
        LOG.trace("Sending Heartbeat: ${api.responses}")
        val hb = jsonObject {
            this["op"] = OpCode.HEARTBEAT
            this["d"] = api.responses
        }.toString()

        if(!sendText(hb, true))
            rateLimitQueue.addLast(hb)
        heartbeatStart = currentTime
    }
}
