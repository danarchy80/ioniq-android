package com.ioniq.ha

import com.ioniq.data.model.VehicleTelemetry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

/**
 * Home Assistant WebSocket client for:
 * 1. Streaming vehicle telemetry TO Home Assistant as sensor updates
 * 2. Receiving commands FROM Home Assistant (e.g., start/stop monitoring)
 *
 * Protocol: HA WebSocket API (https://developers.home-assistant.io/docs/api/websocket)
 */
class HomeAssistantClient(
    private val baseUrl: String,          // e.g. "ws://homeassistant.local:8123/api/websocket"
    private val longLivedToken: String     // HA long-lived access token
) {
    enum class HaState { DISCONNECTED, CONNECTING, AUTHENTICATED, ERROR }

    private val _state = MutableStateFlow(HaState.DISCONNECTED)
    val state: StateFlow<HaState> = _state.asStateFlow()

    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private var messageId = AtomicInteger(1)

    private val httpClient = OkHttpClient.Builder()
        .pingInterval(java.time.Duration.ofSeconds(30))
        .build()

    private val _incomingCommands = MutableSharedFlow<HaCommand>(replay = 0)
    val incomingCommands: SharedFlow<HaCommand> = _incomingCommands.asSharedFlow()

    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 15

    data class HaCommand(val action: String, val payload: Map<String, Any?>)

    /**
     * Connect to Home Assistant WebSocket API.
     */
    fun connect() {
        clientScope.launch {
            _state.value = HaState.CONNECTING
            Timber.i("Connecting to HA WebSocket: $baseUrl")

            val request = Request.Builder().url(baseUrl).build()
            webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Timber.i("HA WebSocket opened — waiting for auth_required")
                    reconnectAttempts = 0  // reset on successful connection
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        handleMessage(JSONObject(text))
                    } catch (e: Exception) {
                        Timber.e(e, "Error parsing HA message: $text")
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Timber.i("HA WebSocket closing: $code $reason")
                    _state.value = HaState.DISCONNECTED
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Timber.i("HA WebSocket closed: $code $reason")
                    _state.value = HaState.DISCONNECTED
                    scheduleReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Timber.e(t, "HA WebSocket failure")
                    _state.value = HaState.ERROR
                    scheduleReconnect()
                }
            })
        }
    }

    private fun handleMessage(json: JSONObject) {
        when (json.optString("type")) {
            "auth_required" -> {
                // Send auth message
                sendJson(JSONObject().apply {
                    put("type", "auth")
                    put("access_token", longLivedToken)
                })
            }
            "auth_ok" -> {
                _state.value = HaState.AUTHENTICATED
                Timber.i("HA WebSocket authenticated")
                // Subscribe to events if needed
                subscribeToEvents()
            }
            "auth_invalid" -> {
                Timber.e("HA auth invalid: ${json.optString("message")}")
                _state.value = HaState.ERROR
                webSocket?.close(1008, "Auth invalid")
            }
            "event" -> {
                parseEvent(json)
            }
            "result" -> {
                val success = json.optBoolean("success", false)
                Timber.d("HA result: success=$success")
            }
        }
    }

    private fun subscribeToEvents() {
        // Subscribe to ioniq commands (custom events from HA automations)
        sendJson(JSONObject().apply {
            put("id", messageId.getAndIncrement())
            put("type", "subscribe_events")
            put("event_type", "ioniq_command")
        })
    }

    private fun parseEvent(json: JSONObject) {
        val event = json.optJSONObject("event") ?: return
        val eventdata = event.optJSONObject("data") ?: return
        val action = eventdata.optString("action", "")
        val payload = mutableMapOf<String, Any?>()
        eventdata.keys().forEach { key ->
            if (key != "action") payload[key] = eventdata.opt(key)
        }
        if (action.isNotEmpty()) {
            clientScope.launch {
                _incomingCommands.emit(HaCommand(action, payload))
            }
        }
    }

    /**
     * Push vehicle telemetry to Home Assistant as sensor state updates.
     */
    fun pushTelemetry(telemetry: VehicleTelemetry) {
        if (_state.value != HaState.AUTHENTICATED) return

        val msgId = messageId.getAndIncrement()

        // Fire event with telemetry data
        sendJson(JSONObject().apply {
            put("id", msgId)
            put("type", "fire_event")
            put("event_type", "ioniq_telemetry")
            put("event_data", JSONObject().apply {
                put("soc", telemetry.soc)
                put("battery_voltage", telemetry.batteryVoltage)
                put("battery_current", telemetry.batteryCurrent)
                put("charging_state", telemetry.chargingState.name)
                put("charge_power_kw", telemetry.chargePower)
                put("battery_temp_c", telemetry.batteryTempMax)
                put("inlet_temp_c", telemetry.inletTemp)
                put("ambient_temp_c", telemetry.ambientTemp)
                put("cell_voltage_min_mv", telemetry.cellVoltageMin)
                put("cell_voltage_max_mv", telemetry.cellVoltageMax)
                put("cumulative_charge_kwh", telemetry.cumulativeEnergyCharged)
                put("cumulative_discharge_kwh", telemetry.cumulativeEnergyDischarged)
                put("timestamp", telemetry.timestamp)
            })
        })
    }

    private fun sendJson(json: JSONObject) {
        webSocket?.send(json.toString())
        Timber.v("HA → ${json.optString("type")}")
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= maxReconnectAttempts) {
            Timber.w("HA reconnect giving up after $maxReconnectAttempts attempts")
            _state.value = HaState.ERROR
            return
        }
        reconnectAttempts++
        val delayMs = (5000L * (1L shl (reconnectAttempts - 1).coerceAtMost(5))).coerceAtMost(30000L)
        Timber.i("HA reconnect attempt $reconnectAttempts/$maxReconnectAttempts in ${delayMs}ms")
        clientScope.launch {
            delay(delayMs)
            if (_state.value != HaState.AUTHENTICATED) {
                connect()
            }
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnect")
        _state.value = HaState.DISCONNECTED
    }

    fun release() {
        disconnect()
        httpClient.dispatcher.executorService.shutdown()
        clientScope.cancel()
    }
}
