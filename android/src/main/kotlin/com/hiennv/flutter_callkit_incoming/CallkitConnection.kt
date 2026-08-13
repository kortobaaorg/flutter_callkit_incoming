package com.hiennv.flutter_callkit_incoming

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.TelecomManager
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.ConcurrentHashMap

/**
 * Self-managed Telecom [Connection] implementation for flutter_callkit_incoming.
 *
 * Hosts the call in the Android Telecom framework with `PROPERTY_SELF_MANAGED` so
 * the OS treats it as a first-party phone call — granting it keyguard-bypass /
 * full-screen-intent priority on strict OEMs (Samsung Knox, Xiaomi, etc.) that
 * otherwise ignore [android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED]
 * on ordinary Activities.
 *
 * Lifecycle:
 *  1. [CallkitConnectionService.onCreateIncomingConnection] returns a
 *     ringing Connection instance.
 *  2. User taps Accept in our notification → plugin's BroadcastReceiver maps the
 *     broadcast to the matching connection via [find] and calls [setActive].
 *  3. User taps Decline / End → [setDisconnected] + [destroy] → unregister.
 *
 * Connection lookup uses a process-wide [ConcurrentHashMap] keyed by the call id
 * (the plugin's existing `Data.id` field). The OS holds the Connection object in
 * the Telecom framework; we keep a parallel reference so our BroadcastReceiver
 * can drive state transitions.
 */
@RequiresApi(Build.VERSION_CODES.M)
class CallkitConnection(
    private val appContext: Context,
    val callId: String,
    val bundle: Bundle,
) : Connection() {

    companion object {
        private const val TAG = "CallkitConnection"
        private const val RESUME_POLL_GRACE_MS = 2000L
        private const val RESUME_POLL_INTERVAL_MS = 1000L

        /** Bundle key — pass the full call Data bundle through Telecom extras. */
        const val EXTRA_CALL_BUNDLE = "com.hiennv.flutter_callkit_incoming.CALL_BUNDLE"

        private val activeConnections = ConcurrentHashMap<String, CallkitConnection>()

        fun find(callId: String): CallkitConnection? = activeConnections[callId]

        fun register(callId: String, conn: CallkitConnection) {
            activeConnections[callId] = conn
        }

        fun unregister(callId: String) {
            activeConnections.remove(callId)
        }

        /** For testing / cleanup — release all refs (Connection objects already destroyed by OS). */
        fun clearAll() {
            activeConnections.clear()
        }

        fun activeCount(): Int = activeConnections.size
    }

    init {
        connectionProperties = PROPERTY_SELF_MANAGED
        audioModeIsVoip = true
        // CAPABILITY_HOLD ("can be held right now") and CAPABILITY_SUPPORT_HOLD
        // ("hold feature exists") are distinct flags and Telecom requires BOTH:
        // when the user answers another call, CallsManager only holds the active
        // call if it has CAPABILITY_HOLD — otherwise onHold() is never invoked
        // and the call is disconnected or left running unheld.
        connectionCapabilities = CAPABILITY_MUTE or CAPABILITY_HOLD or CAPABILITY_SUPPORT_HOLD
        register(callId, this)
        Log.d(TAG, "Connection created id=$callId active=${activeCount()}")
    }

    // -------------------------------------------------------------------------
    // Telecom → app lifecycle callbacks
    //
    // These fire when the user interacts with the OS-level call UI (system
    // dialer, car Bluetooth, watch, etc.). In our app-driven model we still
    // handle the primary Accept/Decline via our own notification buttons, but
    // the OS can also trigger these — we must honor both paths.
    // -------------------------------------------------------------------------

    override fun onAnswer() {
        super.onAnswer()
        Log.d(TAG, "onAnswer id=$callId")
        setActive()
    }

    override fun onReject() {
        super.onReject()
        Log.d(TAG, "onReject id=$callId")
        finishWithCause(DisconnectCause.REJECTED)
    }

    override fun onDisconnect() {
        super.onDisconnect()
        Log.d(TAG, "onDisconnect id=$callId")
        notifyDartCallEnded()
        finishWithCause(DisconnectCause.LOCAL)
    }

    override fun onAbort() {
        super.onAbort()
        Log.d(TAG, "onAbort id=$callId")
        notifyDartCallEnded()
        finishWithCause(DisconnectCause.UNKNOWN)
    }

    /**
     * Tell Dart the call is over when the teardown came from Telecom rather
     * than from us.
     *
     * These callbacks fire when the OS ends the call — the system call UI, a
     * car head unit, or Android's own CallAnomalyWatchdog. Nothing forwarded
     * them, so the Flutter side never learned: on 2026-08-13 Telecom fully
     * disconnected at 17:43:14 (`Call count decrement 0`, `Service unbound`)
     * while the app still showed a running call and a counting timer minutes
     * later.
     *
     * Deliberately not called from [markEnded] / [markDeclined]: those are the
     * app driving Telecom, so the app already knows and re-emitting would
     * bounce the event back into the teardown it is already running.
     */
    private fun notifyDartCallEnded() {
        // Only for a call that actually got going. A connection that never
        // reached ACTIVE is one Telecom is tidying up — the anomaly watchdog
        // firing on a stuck outgoing call, say — and the app's own ringing
        // and no-answer handling owns that window. Forwarding it ended live
        // calls at ~100 seconds on 2026-08-13.
        if (!hasBeenActive) {
            Log.d(TAG, "not notifying Dart, connection was never active id=$callId")
            return
        }
        FlutterCallkitIncomingPlugin.sendEvent(
            CallkitConstants.ACTION_CALL_ENDED,
            mapOf("id" to callId),
        )
    }

    // Telecom holds this self-managed connection when another call takes over
    // the audio (e.g. the user dials or answers a cellular call). Forward the
    // transition to Dart so the app can pause its media — mirrors what iOS
    // does via CXSetHeldCallAction.
    //
    // Telecom does NOT unhold self-managed calls when the other call ends —
    // the self-managed contract is that the app resumes its own call. So on
    // hold we start a watcher that polls for the managed call's presence and
    // self-resumes once it is gone (see resumeWhenManagedCallEnds).
    override fun onHold() {
        super.onHold()
        Log.d(TAG, "onHold id=$callId")
        setOnHold()
        FlutterCallkitIncomingPlugin.sendEvent(
            CallkitConstants.ACTION_CALL_TOGGLE_HOLD,
            mapOf("id" to callId, "isOnHold" to true),
        )
        startResumeWatcher()
    }

    /**
     * While a Telecom call is ringing, volume-key presses never reach
     * AudioService — PhoneWindowManager intercepts them and calls
     * [android.telecom.TelecomManager.silenceRinger], which lands here.
     * Self-managed connections do their own ringing, so stop our ringtone.
     */
    override fun onSilence() {
        super.onSilence()
        Log.d(TAG, "onSilence id=$callId")
        FlutterCallkitIncomingPlugin.getInstance()?.getCallkitSoundPlayerManager()?.stop()
    }

    override fun onUnhold() {
        super.onUnhold()
        Log.d(TAG, "onUnhold id=$callId")
        resume()
    }

    // -------------------------------------------------------------------------
    // Audio routing
    //
    // KORTOBAA fork addition (2026-08-13). Telecom picks the route for a
    // self-managed connection itself and applies it as a privileged client:
    // `setCommunicationDevice(deviceType=1)` from uid 1000, roughly ten
    // seconds after the connection goes active. That silently overrode the
    // app's own `setSpeakerphoneOn(true)` — a call selected as speaker came
    // out of the earpiece, and only toggling the speaker a second time, after
    // Telecom had settled, made it stick.
    //
    // The app's request is non-privileged and loses that race every time, so
    // the route has to be asked for through Telecom instead. Remember what
    // was asked for and re-assert it whenever Telecom moves us off it.
    // -------------------------------------------------------------------------

    @Volatile
    private var desiredRoute: Int? = null

    /** Route this call through [route], one of `CallAudioState.ROUTE_*`. */
    fun applyAudioRoute(route: Int) {
        desiredRoute = route
        Log.d(TAG, "applyAudioRoute id=$callId route=$route")
        try {
            setAudioRoute(route)
        } catch (e: Exception) {
            Log.w(TAG, "setAudioRoute failed: ${e.message}")
        }
    }

    override fun onCallAudioStateChanged(state: CallAudioState) {
        super.onCallAudioStateChanged(state)
        val desired = desiredRoute
        Log.d(
            TAG,
            "onCallAudioStateChanged id=$callId route=${state.route} " +
                "supported=${state.supportedRouteMask} desired=$desired",
        )
        if (desired == null || state.route == desired) return
        // Only re-assert a route the device actually offers, otherwise this
        // loops: Telecom rejects the request, fires the callback again with
        // the old route, and we ask once more.
        if (state.supportedRouteMask and desired == 0) {
            Log.d(TAG, "desired route $desired unsupported — leaving ${state.route}")
            desiredRoute = null
            return
        }
        Log.d(TAG, "re-asserting route $desired over ${state.route}")
        try {
            setAudioRoute(desired)
        } catch (e: Exception) {
            Log.w(TAG, "re-assert failed: ${e.message}")
        }
    }

    private fun resume() {
        stopResumeWatcher()
        setActive()
        FlutterCallkitIncomingPlugin.sendEvent(
            CallkitConstants.ACTION_CALL_TOGGLE_HOLD,
            mapOf("id" to callId, "isOnHold" to false),
        )
    }

    // -------------------------------------------------------------------------
    // Self-resume watcher
    // -------------------------------------------------------------------------

    private val watcherHandler = Handler(Looper.getMainLooper())
    private var resumeWatcher: Runnable? = null

    private fun startResumeWatcher() {
        stopResumeWatcher()
        val watcher = object : Runnable {
            override fun run() {
                if (state != STATE_HOLDING) {
                    resumeWatcher = null
                    return
                }
                if (isManagedCallActive()) {
                    watcherHandler.postDelayed(this, RESUME_POLL_INTERVAL_MS)
                    return
                }
                Log.d(TAG, "managed call gone — self-resuming id=$callId")
                resumeWatcher = null
                resume()
            }
        }
        resumeWatcher = watcher
        // Grace delay before the first check: at the instant Telecom holds us
        // the native call may still be setting up, so an immediate poll could
        // read "no managed call" and resume prematurely.
        watcherHandler.postDelayed(watcher, RESUME_POLL_GRACE_MS)
    }

    private fun stopResumeWatcher() {
        resumeWatcher?.let { watcherHandler.removeCallbacks(it) }
        resumeWatcher = null
    }

    /**
     * Is a managed (carrier/telephony) call ringing, dialing or active?
     *
     * Primary signal: [TelecomManager.isInManagedCall] — precise, counts only
     * managed calls (never our own self-managed one), but needs the
     * READ_PHONE_STATE runtime permission. Fallback when not granted: the
     * audio mode, which telephony sets to MODE_IN_CALL (active) or
     * MODE_RINGTONE (ringing) for the lifetime of a native call.
     */
    private fun isManagedCallActive(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val telecom =
                appContext.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (telecom != null) {
                try {
                    return telecom.isInManagedCall
                } catch (e: SecurityException) {
                    Log.d(TAG, "isInManagedCall needs READ_PHONE_STATE — audio-mode fallback")
                }
            }
        }
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return false
        return audio.mode == AudioManager.MODE_IN_CALL ||
            audio.mode == AudioManager.MODE_RINGTONE
    }

    // -------------------------------------------------------------------------
    // App → Telecom driving helpers (invoked by the plugin's BroadcastReceiver)
    // -------------------------------------------------------------------------

    /**
     * Whether Telecom has ever seen this connection go active.
     *
     * A self-managed connection that never reaches ACTIVE is treated as
     * stuck by Android's CallAnomalyWatchdog, which disconnects it with
     * `REQUEST_DISCONNECT, State timeout` after roughly 100 seconds. Nothing
     * in this plugin marked an *outgoing* call active — `markAccepted` is
     * only reached from the accept/connected broadcasts, which the app never
     * sent — so every Android-initiated call was quietly killed at ~100s.
     * It went unnoticed because the disconnect was not forwarded to Dart and
     * the app carried on regardless.
     */
    @Volatile
    var hasBeenActive: Boolean = false
        private set

    /** Mark the call as answered — user accepted via app notification. */
    fun markAccepted() {
        Log.d(TAG, "markAccepted id=$callId")
        hasBeenActive = true
        setActive()
    }

    /**
     * Mark the call as declined/ended — user declined via app notification.
     *
     * Cold-launch DECLINE recovery is fired
     * from inside the self-managed [Connection] context (and *before*
     * [setDisconnected] runs) so the call is still in the RINGING state when
     * [Context.startActivity] is invoked. The hope: Android 14+ BAL grants a
     * PHONE_CALL exemption for active self-managed Telecom calls. Previous
     * placement inside the BroadcastReceiver hit BAL_BLOCK with
     * `callingUidProcState: BOUND_FOREGROUND_SERVICE; callingUidHasVisibleActivity: false`
     * (Galaxy logcat 2026-05-05 03:49:04.039).
     *
     * If this position still BAL_BLOCK's, the next escalation is to promote
     * `CallkitConnectionService` to a `foregroundServiceType="phoneCall"`
     * FGS for the lifetime of the connection.
     */
    fun markDeclined(context: Context) {
        Log.d(TAG, "markDeclined id=$callId")
        // Do not launch the app on decline. End the self-managed Telecom call
        // immediately so declining from the notification/lock screen leaves a
        // backgrounded or terminated app closed (3.0.0 behavior).
        finishWithCause(DisconnectCause.REJECTED)
    }

    /**
     * Persist declined nonce and start MainActivity. Idempotent — safe even
     * if the BroadcastReceiver fallback also fires (single-task launch flag,
     * one-shot consumePending).
     */
    private fun triggerDeclineRecovery(context: Context) {
        try {
            val prefs = context.getSharedPreferences(
                "flutter_callkit_incoming_decline",
                Context.MODE_PRIVATE,
            )
            prefs.edit()
                .putString("pending_nonce", callId)
                .putLong("pending_at", System.currentTimeMillis())
                .apply()
            Log.d(TAG, "[DIAG-DECLINE-CS] prefs written nonce=$callId")
            val launchIntent = AppUtils.getAppIntent(context, null, null)
            launchIntent?.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            )
            if (launchIntent != null) {
                context.startActivity(launchIntent)
                Log.d(TAG, "[DIAG-DECLINE-CS] startActivity dispatched (CS ctx, RINGING)")
            } else {
                Log.w(TAG, "[DIAG-DECLINE-CS] launchIntent null — recovery skipped")
            }
        } catch (e: Exception) {
            Log.w(TAG, "[DIAG-DECLINE-CS] recovery failed: ${e.message}")
        }
    }

    /** Mark the call as terminated — call ended (either side hung up). */
    fun markEnded() {
        Log.d(TAG, "markEnded id=$callId")
        finishWithCause(DisconnectCause.LOCAL)
    }

    /** Mark the call as missed — timeout without answer. */
    fun markMissed() {
        Log.d(TAG, "markMissed id=$callId")
        finishWithCause(DisconnectCause.MISSED)
    }

    private fun finishWithCause(cause: Int) {
        stopResumeWatcher()
        try {
            setDisconnected(DisconnectCause(cause))
        } catch (e: Exception) {
            Log.w(TAG, "setDisconnected failed: ${e.message}")
        }
        unregister(callId)
        try {
            destroy()
        } catch (e: Exception) {
            Log.w(TAG, "destroy failed: ${e.message}")
        }
    }
}
