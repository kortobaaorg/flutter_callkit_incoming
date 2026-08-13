package com.hiennv.flutter_callkit_incoming

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Build
import android.telecom.CallAudioState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.NonNull
import com.hiennv.flutter_callkit_incoming.Utils.Companion.reapCollection
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.*
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.lang.ref.WeakReference


/** FlutterCallkitIncomingPlugin */
@SuppressLint("LongLogTag")
class FlutterCallkitIncomingPlugin : FlutterPlugin, MethodCallHandler, ActivityAware,
    PluginRegistry.RequestPermissionsResultListener {
    companion object {

        const val EXTRA_CALLKIT_CALL_DATA = "EXTRA_CALLKIT_CALL_DATA"

        const val TAG = "FlutterCallkitIncomingPlugin"

        @SuppressLint("StaticFieldLeak")
        private lateinit var instance: FlutterCallkitIncomingPlugin

        fun getInstance(): FlutterCallkitIncomingPlugin? {
            if (hasInstance()) {
                return instance
            }
            return null
        }

        fun hasInstance(): Boolean {
            return ::instance.isInitialized
        }

        private val methodChannels = mutableMapOf<BinaryMessenger, MethodChannel>()
        private val eventChannels = mutableMapOf<BinaryMessenger, EventChannel>()
        private val eventHandlers = mutableMapOf<BinaryMessenger, EventCallbackHandler>()
        private val eventCallbacks = mutableListOf<WeakReference<CallkitEventCallback>>()

        fun sendEvent(event: String, body: Map<String, Any?>) {
            send(event, body)
        }

        fun sendEventCustom(event: String, body: Map<String, Any>) {
            send(event, body)
        }

        fun acceptCallHandleCallback(bundle: Bundle) {
            Handler(Looper.getMainLooper()).postDelayed({
                val extra = bundle.getSerializable(CallkitConstants.EXTRA_CALLKIT_EXTRA)
                methodChannels.values.forEach {
                    it.invokeMethod("acceptCallHandle", extra)
                }
            }, 750)
        }

        fun invokeFlutterCallback(data: Any) {
            Handler(Looper.getMainLooper()).postDelayed({
                methodChannels.values.forEach {
                    it.invokeMethod("invokeFlutter", data)
                }
            }, 750)
        }

        /**
         * Send event to Flutter UI if there are active handlers, otherwise send to background
         * executor if registered.
         */
        private fun send(event: String, body: Map<String, Any?>) {
            val uiHandlers = eventHandlers.values.filter { it.hasListener() }
            if (uiHandlers.isNotEmpty()) {
                Log.d(TAG, "Sending UI event: $event")
                uiHandlers.forEach { it.send(event, body) }
            } else if (CallkitBackgroundExecutor.registered) {
                Log.d(TAG, "Sending background event: $event (no UI handlers)")
                CallkitBackgroundExecutor.send(event, body)
            }
        }

        /**
         * Register a callback to receive call events (accept/decline) natively.
         * This allows other plugins/services to handle call events
         * even when Flutter engine is terminated.
         */
        fun registerEventCallback(callback: CallkitEventCallback) {
            eventCallbacks.add(WeakReference(callback))
        }

        /**
         * Unregister an event callback.
         */
        fun unregisterEventCallback(callback: CallkitEventCallback) {
            eventCallbacks.removeAll { it.get() == callback || it.get() == null }
        }

        /**
         * Notify all registered event callbacks.
         * Called internally when a call event occurs.
         */
        internal fun notifyEventCallbacks(event: CallkitEventCallback.CallEvent, callData: android.os.Bundle) {
            eventCallbacks.reapCollection().forEach { callbackRef ->
                callbackRef.get()?.onCallEvent(event, callData)
            }
        }


        fun sharePluginWithRegister(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
            initSharedInstance(
                flutterPluginBinding.applicationContext,
                flutterPluginBinding.binaryMessenger
            )
        }

        fun initSharedInstance(context: Context, binaryMessenger: BinaryMessenger) {
            if (!::instance.isInitialized) {
                instance = FlutterCallkitIncomingPlugin()
            }
            // Restore instance fields that may have been nulled during a previous
            // detach. The static `instance` survives across FlutterEngine teardown
            // when the host process is kept alive (e.g. foreground services),
            // so `onAttachedToEngine` must refresh these fields every time;
            // otherwise background-isolate calls end up as silent no-ops.
            if (instance.callkitSoundPlayerManager == null) {
                instance.callkitSoundPlayerManager = CallkitSoundPlayerManager(context)
            }
            if (instance.callkitNotificationManager == null) {
                instance.callkitNotificationManager = CallkitNotificationManager(context, instance.callkitSoundPlayerManager)
            }
            if (instance.context == null) {
                instance.context = context
            } else {
                // Re-initialize managers if they were destroyed but instance still exists
                if (instance.callkitNotificationManager == null) {
                    instance.callkitSoundPlayerManager = CallkitSoundPlayerManager(context)
                    instance.callkitNotificationManager = CallkitNotificationManager(context, instance.callkitSoundPlayerManager)
                }
            }

            val channel = MethodChannel(binaryMessenger, "flutter_callkit_incoming")
            methodChannels[binaryMessenger] = channel
            channel.setMethodCallHandler(instance)

            val events = EventChannel(binaryMessenger, "flutter_callkit_incoming_events")
            eventChannels[binaryMessenger] = events
            val handler = EventCallbackHandler()
            eventHandlers[binaryMessenger] = handler
            events.setStreamHandler(handler)

        }

    }

    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private var activity: Activity? = null
    private var context: Context? = null
    private var callkitNotificationManager: CallkitNotificationManager? = null
    private var callkitSoundPlayerManager: CallkitSoundPlayerManager? = null

    fun getCallkitNotificationManager(): CallkitNotificationManager? {
        return callkitNotificationManager
    }

    fun getCallkitSoundPlayerManager(): CallkitSoundPlayerManager? {
        return callkitSoundPlayerManager
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        sharePluginWithRegister(flutterPluginBinding)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            InAppCallManager(flutterPluginBinding.applicationContext).registerPhoneAccount()
        }
    }

    public fun showIncomingNotification(data: Data) {
        data.from = "notification"
        //send BroadcastReceiver
        context?.sendBroadcast(
            CallkitIncomingBroadcastReceiver.getIntentIncoming(
                requireNotNull(context),
                data.toBundle()
            )
        )
    }

    public fun showMissCallNotification(data: Data) {
        callkitNotificationManager?.showMissCallNotification(data.toBundle())
    }

    public fun startCall(data: Data) {
        context?.sendBroadcast(
            CallkitIncomingBroadcastReceiver.getIntentStart(
                requireNotNull(context),
                data.toBundle()
            )
        )
    }

    public fun endCall(data: Data) {
        context?.sendBroadcast(
            CallkitIncomingBroadcastReceiver.getIntentEnded(
                requireNotNull(context),
                data.toBundle()
            )
        )
    }

    public fun endAllCalls() {
        val calls = getDataActiveCalls(context)
        calls.forEach {
            context?.sendBroadcast(
                CallkitIncomingBroadcastReceiver.getIntentEnded(
                    requireNotNull(context),
                    it.toBundle()
                )
            )
        }
        removeAllCalls(context)
    }

    /**
     * KORTOBAA fork fix (2026-04-17): force-release Android Telecom
     * CommSess + audio state after a call ends.
     *
     * Upstream `flutter_callkit_incoming` only dismisses the notification
     * UI on `endAllCalls`. Android Telecom's CallAudioWatchdog keeps the
     * SelfManaged PhoneAccount's communication session alive for minutes
     * afterwards, silently rejecting the NEXT incoming call's
     * `showCallkitIncoming` request on Xiaomi MIUI and other vendors.
     *
     * This helper:
     *   1. Stops the foreground CallkitNotificationService (pulls the
     *      persistent ongoing-call notification).
     *   2. Resets AudioManager MODE to MODE_NORMAL so WebRTC / flutter_webrtc
     *      doesn't leak MODE_IN_COMMUNICATION into the next call.
     *
     * Evidence: live adb logcat showed `Telecom: CallAudioWatchdog:
     * CommSess{duration=311s}` still alive 5 minutes after Call 1 ended;
     * Call 2's `ACTION_CALL_INCOMING` broadcast fired but no UI displayed.
     * Research citations: react-native-callkeep #665, SignalWire zombie-call.
     */
    private fun forceResetTelecomState() {
        val ctx = context ?: return
        try {
            CallkitNotificationService.stopService(ctx)
        } catch (t: Throwable) {
            android.util.Log.w("FlutterCallkitIncoming", "forceResetTelecomState: stopService failed: $t")
        }
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            am?.mode = android.media.AudioManager.MODE_NORMAL
        } catch (t: Throwable) {
            android.util.Log.w("FlutterCallkitIncoming", "forceResetTelecomState: audio mode reset failed: $t")
        }
        android.util.Log.i("FlutterCallkitIncoming", "forceResetTelecomState: stopped service + MODE_NORMAL")
    }

    fun sendEventCustom(body: Map<String, Any>) {
        send(CallkitConstants.ACTION_CALL_CUSTOM, body)
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        try {
            when (call.method) {
                "registerBackgroundHandler" -> {
                    val args = call.arguments as Map<*, *>
                    val pluginHandle = (args["pluginHandle"] as Number).toLong()
                    val userHandle = (args["userHandle"] as Number).toLong()
                    addBackgroundCallback(context, pluginHandle, userHandle)
                    CallkitBackgroundExecutor.start(requireNotNull(context), pluginHandle)
                    result.success(null)
                }

                "getBackgroundHandler" -> {
                    val handle = getUserCallback(context)
                    result.success(handle)
                }

                "setAcceptCallHandle" -> {
                    val args = call.arguments as? List<*>
                    if (args != null && args.size >= 2) {
                        val handle = args[0] as? Int ?: 0
                        val key = args[1] as? String ?: ""
                        saveHandle(context, key, handle)
                    }
                    result.success(null)
                }

                "showCallkitIncoming" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    data.from = "notification"
                    //send BroadcastReceiver
                    context?.sendBroadcast(
                        CallkitIncomingBroadcastReceiver.getIntentIncoming(
                            requireNotNull(context),
                            data.toBundle()
                        )
                    )

                    result.success(true)
                }

                "showCallkitIncomingSilently" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    data.from = "notification"

                    result.success(true)
                }

                "showMissCallNotification" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    data.from = "notification"
                    callkitNotificationManager?.showMissCallNotification(data.toBundle())
                    result.success(true)
                }

                "startCall" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    context?.sendBroadcast(
                        CallkitIncomingBroadcastReceiver.getIntentStart(
                            requireNotNull(context),
                            data.toBundle()
                        )
                    )

                    result.success(true)
                }

                "muteCall" -> {
                    val map = buildMap {
                        val args = call.arguments
                        if (args is Map<*, *>) {
                            putAll(args as Map<String, Any>)
                        }
                    }
                    sendEvent(CallkitConstants.ACTION_CALL_TOGGLE_MUTE, map)

                    result.success(true)
                }

                "holdCall" -> {
                    val map = buildMap {
                        val args = call.arguments
                        if (args is Map<*, *>) {
                            putAll(args as Map<String, Any>)
                        }
                    }
                    sendEvent(CallkitConstants.ACTION_CALL_TOGGLE_HOLD, map)

                    result.success(true)
                }

                "isMuted" -> {
                    result.success(true)
                }

                "endCall" -> {
                    val calls = getDataActiveCalls(context)
                    val data = Data(call.arguments() ?: HashMap())
                    val currentCall = calls.firstOrNull { it.id == data.id }
                    if (currentCall != null && context != null) {
                        if(currentCall.isAccepted) {
                            context?.sendBroadcast(
                                CallkitIncomingBroadcastReceiver.getIntentEnded(
                                    requireNotNull(context),
                                    currentCall.toBundle()
                                )
                            )
                        }else {
                            context?.sendBroadcast(
                                CallkitIncomingBroadcastReceiver.getIntentDecline(
                                    requireNotNull(context),
                                    currentCall.toBundle()
                                )
                            )
                        }
                    }
                    result.success(true)
                }

                "callConnected" -> {
                    val calls = getDataActiveCalls(context)
                    val data = Data(call.arguments() ?: HashMap())
                    val currentCall = calls.firstOrNull { it.id == data.id }
                    if (currentCall != null && context != null) {
                        context?.sendBroadcast(
                            CallkitIncomingBroadcastReceiver.getIntentConnected(
                                requireNotNull(context),
                                currentCall.toBundle()
                            )
                        )
                    }
                    result.success(true)
                }

                "endAllCalls" -> {
                    val calls = getDataActiveCalls(context)
                    calls.forEach {
                        if (it.isAccepted) {
                            context?.sendBroadcast(
                                CallkitIncomingBroadcastReceiver.getIntentEnded(
                                    requireNotNull(context),
                                    it.toBundle()
                                )
                            )
                        } else {
                            context?.sendBroadcast(
                                CallkitIncomingBroadcastReceiver.getIntentDecline(
                                    requireNotNull(context),
                                    it.toBundle()
                                )
                            )
                        }
                    }
                    removeAllCalls(context)
                    // KORTOBAA 2026-04-17 re-call fix:
                    // Explicitly stop CallkitNotificationService + reset
                    // AudioManager mode so Android TelecomManager's
                    // CallAudioWatchdog drops the SelfManaged CommSess that
                    // otherwise blocks Call 2's incoming UI.
                    // See: react-native-callkeep #665, SignalWire zombie-call pattern.
                    forceResetTelecomState()
                    result.success(true)
                }

                "forceResetCallState" -> {
                    // KORTOBAA 2026-04-17: nuclear reset exposed from Dart.
                    // Equivalent to endAllCalls + force-release native Telecom + audio state.
                    val calls = getDataActiveCalls(context)
                    calls.forEach {
                        context?.sendBroadcast(
                            CallkitIncomingBroadcastReceiver.getIntentEnded(
                                requireNotNull(context),
                                it.toBundle()
                            )
                        )
                    }
                    removeAllCalls(context)
                    forceResetTelecomState()
                    result.success(true)
                }

                "activeCalls" -> {
                    result.success(getDataActiveCallsForFlutter(context))
                }

                "getDevicePushTokenVoIP" -> {
                    result.success("")
                }

                "silenceEvents" -> {
                    val silence = call.arguments as? Boolean ?: false
                    CallkitIncomingBroadcastReceiver.silenceEvents = silence
                    result.success(true)
                }

                "requestNotificationPermission" -> {
                    val map = buildMap {
                        val args = call.arguments
                        if (args is Map<*, *>) {
                            putAll(args as Map<String, Any>)
                        }
                    }
                    callkitNotificationManager?.requestNotificationPermission(activity, map)
                    result.success(true)
                }

                "requestFullIntentPermission" -> {
                    callkitNotificationManager?.requestFullIntentPermission(activity)
                    result.success(true)
                }

                "canUseFullScreenIntent" -> {
                    result.success(callkitNotificationManager?.canUseFullScreenIntent() ?: true)
                }

                // EDIT - clear the incoming notification/ring (after accept/decline/timeout)
                "hideCallkitIncoming" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    callkitSoundPlayerManager?.stop()
                    callkitNotificationManager?.clearIncomingNotification(data.toBundle(), false)
                    result.success(true)
                }

                "endNativeSubsystemOnly" -> {
                    result.success(true)
                }

                // KORTOBAA fork (2026-08-13): was a no-op stub, so the app's
                // speaker choice only ever went to AudioManager — where
                // Telecom, being privileged, overrode it moments later. Route
                // through the self-managed Connection instead, which Telecom
                // honours and will not fight.
                "setAudioRoute" -> {
                    val args = call.arguments<HashMap<String, Any?>>() ?: HashMap()
                    val id = args["id"] as? String
                    val route = args["route"] as? String
                    val telecomRoute = when (route) {
                        "speaker" -> CallAudioState.ROUTE_SPEAKER
                        "earpiece" -> CallAudioState.ROUTE_EARPIECE
                        "bluetooth" -> CallAudioState.ROUTE_BLUETOOTH
                        "wired" -> CallAudioState.ROUTE_WIRED_HEADSET
                        else -> null
                    }
                    if (id == null || telecomRoute == null) {
                        Log.d(TAG, "setAudioRoute ignored id=$id route=$route")
                        result.success(false)
                        return
                    }
                    val connection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        CallkitConnection.find(id)
                    } else {
                        null
                    }
                    if (connection == null) {
                        // No self-managed connection for this id — the app's
                        // own AudioManager handling is all there is, and it
                        // works unopposed in that case.
                        Log.d(TAG, "setAudioRoute no connection for id=$id")
                        result.success(false)
                        return
                    }
                    connection.applyAudioRoute(telecomRoute)
                    result.success(true)
                }
            }
        } catch (error: Exception) {
            result.error("error", error.message, "")
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannels.remove(binding.binaryMessenger)?.setMethodCallHandler(null)
        eventChannels.remove(binding.binaryMessenger)?.setStreamHandler(null)
        eventHandlers.remove(binding.binaryMessenger)

        // Only destroy and null the shared managers when the LAST engine detaches.
        // When multiple engines are attached (e.g. main UI engine + FCM background
        // isolate engine), tearing down the main engine must not pull the managers
        // out from under the background isolate that still needs them.
        if (methodChannels.isEmpty() && eventChannels.isEmpty()) {
            instance.callkitSoundPlayerManager?.destroy()
            instance.callkitNotificationManager?.destroy()
            instance.callkitSoundPlayerManager = null
            instance.callkitNotificationManager = null
        }
        Log.d(TAG, "onDetachedFromEngine")
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        instance.context = binding.activity.applicationContext
        instance.activity = binding.activity
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        instance.context = binding.activity.applicationContext
        instance.activity = binding.activity
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivity() {
        // Keep instance.context alive. It is the applicationContext, shared by
        // every engine attachment and safe to hold for the lifetime of the JVM.
        // Nulling it here would break background-isolate method channel calls
        // (showCallkitIncoming relies on `context?.sendBroadcast(...)`) whenever
        // the activity is destroyed while a cached background engine keeps the
        // static `instance` alive.
        instance.activity = null
    }

    class EventCallbackHandler : EventChannel.StreamHandler {

        @Volatile
        private var eventSink: EventChannel.EventSink? = null

        fun hasListener(): Boolean = eventSink != null

        override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
            eventSink = sink
        }

        fun send(event: String, body: Map<String, Any?>) {
            val data = mapOf(
                "event" to event,
                "body" to body
            )
            Handler(Looper.getMainLooper()).post {
                eventSink?.success(data)
            }
        }

        override fun onCancel(arguments: Any?) {
            eventSink = null
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        instance.callkitNotificationManager?.onRequestPermissionsResult(
            instance.activity,
            requestCode,
            grantResults
        )
        return true
    }


}
