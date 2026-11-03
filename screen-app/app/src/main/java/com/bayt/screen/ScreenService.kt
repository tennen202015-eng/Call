package com.bayt.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.random.Random

/**
 * يلتقط الشاشة ويرسلها للـ iPad بنفس بروتوكول PeerJS اللي بيستخدمه موقع "مكالمات البيت".
 * الـ iPad بيستقبلها كمكالمة من نوع "screen".
 */
class ScreenService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_DATA = "data"
        const val EXTRA_CODE = "code"
        const val EXTRA_NAME = "name"
        private const val NOTIF_ID = 7
        private const val CHANNEL = "screen"

        @Volatile var running = false
        @Volatile var status = ""
        var listener: ((String, Boolean) -> Unit)? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var stopped = false

    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var helper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var pc: PeerConnection? = null
    private var ws: WebSocket? = null
    private var http: OkHttpClient? = null

    private var target = ""
    private var connId = ""
    private var name = ""
    private var remoteSet = false
    private val pendingCandidates = ArrayList<IceCandidate>()
    private var connected = false

    private val heartbeat = object : Runnable {
        override fun run() {
            ws?.send("{\"type\":\"HEARTBEAT\"}")
            main.postDelayed(this, 5000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAll("تم إيقاف عرض الشاشة")
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        val code = intent?.getStringExtra(EXTRA_CODE) ?: ""
        name = intent?.getStringExtra(EXTRA_NAME) ?: ""
        if (data == null || code.isEmpty()) { stopSelf(); return START_NOT_STICKY }
        running = true
        stopped = false
        goForeground("جارٍ التحضير...")
        try {
            start(data, code)
        } catch (e: Exception) {
            stopAll("حصل خطأ: " + (e.message ?: e.javaClass.simpleName))
        }
        return START_NOT_STICKY
    }

    private fun hasMic() = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ===== الإشعار =====
    private fun buildNotif(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "عرض الشاشة", NotificationManager.IMPORTANCE_LOW))
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(this, 1, Intent(this, ScreenService::class.java).setAction(ACTION_STOP), flags)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("عرض الشاشة على الـ iPad")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "إيقاف", stop).build())
            .build()
    }

    private fun goForeground(text: String) {
        val n = buildNotif(text)
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (hasMic() && Build.VERSION.SDK_INT >= 30) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIF_ID, n, type)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun setStatus(text: String, active: Boolean = true) {
        status = text
        main.post {
            listener?.invoke(text, active)
            if (active && running) {
                try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(text)) } catch (_: Exception) {}
            }
        }
    }

    // ===== التشغيل =====
    private fun start(data: Intent, code: String) {
        val base = "baytcall-$code"
        target = "$base-home"
        val myId = "$base-scr-" + randStr(8)
        connId = "mc_" + randStr(12)

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions()
        )
        val e = EglBase.create()
        egl = e
        val adm = org.webrtc.audio.JavaAudioDeviceModule.builder(this).createAudioDeviceModule()
        val f = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(e.eglBaseContext, true, false))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(e.eglBaseContext))
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()
        factory = f

        // التقاط الشاشة
        val cap = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopAll("تم إيقاف عرض الشاشة") } }
        })
        capturer = cap
        val h = SurfaceTextureHelper.create("capture", e.eglBaseContext)
        helper = h
        val vs = f.createVideoSource(true)
        videoSource = vs
        cap.initialize(h, this, vs.capturerObserver)
        val (w, hh) = captureSize()
        cap.startCapture(w, hh, 15)
        val videoTrack = f.createVideoTrack("screen0", vs)

        // الاتصال
        val ice = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        val cfg = PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val p = f.createPeerConnection(cfg, pcObserver) ?: throw IllegalStateException("تعذّر إنشاء الاتصال")
        pc = p
        p.addTrack(videoTrack, listOf("s0"))
        if (hasMic()) {
            val asrc = f.createAudioSource(MediaConstraints())
            audioSource = asrc
            p.addTrack(f.createAudioTrack("mic0", asrc), listOf("s0"))
            // صوت الـ iPad يطلع من السماعة الخارجية
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = true
            } catch (_: Exception) {}
        } else {
            p.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
        }

        setStatus("جارٍ الاتصال بالخادم...")
        connectSignaling(myId)

        // لو محدش رد خلال دقيقة
        main.postDelayed({ if (running && !connected) stopAll("لم يتم الرد من الـ iPad") }, 60000)
    }

    private fun captureSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        var w = dm.widthPixels
        var h = dm.heightPixels
        val sc = 1280.0 / max(w, h)
        if (sc < 1) { w = (w * sc).toInt(); h = (h * sc).toInt() }
        return Pair(w and 1.inv(), h and 1.inv())
    }

    private fun randStr(n: Int): String {
        val c = "abcdefghijkmnpqrstuvwxyz23456789"
        return (1..n).map { c[Random.nextInt(c.length)] }.joinToString("")
    }

    // ===== خادم PeerJS =====
    private fun connectSignaling(myId: String) {
        val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
        http = client
        val url = "wss://0.peerjs.com/peerjs?key=peerjs&id=$myId&token=${randStr(10)}&version=1.4.7"
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                main.post { onServerMessage(text) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.post { if (running && !connected) stopAll("تعذّر الاتصال بالخادم. تأكد من الإنترنت.") }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {}
        })
    }

    private fun sendServer(type: String, payload: JSONObject) {
        val m = JSONObject().put("type", type).put("payload", payload).put("dst", target)
        ws?.send(m.toString())
    }

    private fun onServerMessage(text: String) {
        if (!running) return
        val m = try { JSONObject(text) } catch (_: Exception) { return }
        val payload = m.optJSONObject("payload")
        when (m.optString("type")) {
            "OPEN" -> {
                main.removeCallbacks(heartbeat)
                main.postDelayed(heartbeat, 5000)
                setStatus("جارٍ الاتصال بالـ iPad...")
                makeOffer()
            }
            "ANSWER" -> {
                if (payload == null || payload.optString("connectionId") != connId) return
                val sdp = payload.optJSONObject("sdp")?.optString("sdp") ?: return
                pc?.setRemoteDescription(object : SdpAdapter() {
                    override fun onSetSuccess() {
                        main.post {
                            remoteSet = true
                            pendingCandidates.forEach { pc?.addIceCandidate(it) }
                            pendingCandidates.clear()
                            if (!connected) setStatus("الـ iPad رد... جارٍ الربط")
                        }
                    }
                }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
            }
            "CANDIDATE" -> {
                if (payload == null || payload.optString("connectionId") != connId) return
                val c = payload.optJSONObject("candidate") ?: return
                val ic = IceCandidate(c.optString("sdpMid"), c.optInt("sdpMLineIndex"), c.optString("candidate"))
                if (remoteSet) pc?.addIceCandidate(ic) else pendingCandidates.add(ic)
            }
            "EXPIRE" -> stopAll("الـ iPad غير متصل الآن. لازم صفحة المكالمات تكون مفتوحة عليه.")
            "LEAVE" -> if (m.optString("src") == target) stopAll("الـ iPad قفل الصفحة")
            "ID-TAKEN", "INVALID-KEY", "ERROR" -> stopAll("خطأ من الخادم، جرّب تاني")
        }
    }

    private fun makeOffer() {
        val p = pc ?: return
        val mc = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        p.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                p.setLocalDescription(object : SdpAdapter() {
                    override fun onSetSuccess() {
                        main.post {
                            val meta = JSONObject().put("type", "screen").put("from", "scr").put("name", name)
                            val payload = JSONObject()
                                .put("sdp", JSONObject().put("type", "offer").put("sdp", desc.description))
                                .put("type", "media")
                                .put("connectionId", connId)
                                .put("metadata", meta)
                                .put("browser", "chrome")
                            sendServer("OFFER", payload)
                            setStatus("في انتظار الرد على الـ iPad...")
                        }
                    }
                }, desc)
            }
            override fun onCreateFailure(error: String?) { main.post { stopAll("تعذّر بدء الاتصال") } }
        }, mc)
    }

    private val pcObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) {
            main.post {
                val cand = JSONObject().put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex)
                sendServer("CANDIDATE", JSONObject().put("candidate", cand).put("type", "media").put("connectionId", connId))
            }
        }
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            main.post {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                        connected = true
                        setStatus("✓ شاشتك معروضة على الـ iPad الآن")
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        setStatus("الاتصال ضعيف...")
                        main.postDelayed({
                            if (running && pc?.iceConnectionState() == PeerConnection.IceConnectionState.DISCONNECTED)
                                stopAll("انتهى العرض (الـ iPad قفل المكالمة)")
                        }, 6000)
                    }
                    PeerConnection.IceConnectionState.FAILED ->
                        stopAll(if (connected) "انقطع الاتصال" else "تعذّر الربط بين الجهازين")
                    PeerConnection.IceConnectionState.CLOSED -> stopAll("انتهى العرض")
                    else -> {}
                }
            }
        }
        override fun onSignalingChange(s: PeerConnection.SignalingState) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
        override fun onAddStream(s: MediaStream) {}
        override fun onRemoveStream(s: MediaStream) {}
        override fun onDataChannel(d: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
    }

    // ===== الإيقاف =====
    private fun stopAll(msg: String) {
        if (stopped) return
        stopped = true
        running = false
        main.removeCallbacksAndMessages(null)
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
        try { capturer?.stopCapture() } catch (_: Exception) {}
        try { capturer?.dispose() } catch (_: Exception) {}
        try { pc?.dispose() } catch (_: Exception) {}
        try { videoSource?.dispose() } catch (_: Exception) {}
        try { audioSource?.dispose() } catch (_: Exception) {}
        try { helper?.dispose() } catch (_: Exception) {}
        try { factory?.dispose() } catch (_: Exception) {}
        try { egl?.release() } catch (_: Exception) {}
        capturer = null; pc = null; videoSource = null; audioSource = null; helper = null; factory = null; egl = null
        try { http?.dispatcher?.executorService?.shutdown() } catch (_: Exception) {}
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
        } catch (_: Exception) {}
        status = msg
        listener?.invoke(msg, false)
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        stopAll("تم إيقاف عرض الشاشة")
        super.onDestroy()
    }
}

open class SdpAdapter : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
