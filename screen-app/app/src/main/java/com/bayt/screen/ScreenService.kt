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
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
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
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * عرض الشاشة بتقنية WebRTC (فيديو حقيقي H.264 + صوت الموبايل الداخلي).
 *  - وضع "net": عبر الإنترنت للـ iPad اللي فاتح موقع مكالمات البيت (بروتوكول PeerJS)
 *  - وضع "local": بدون إنترنت، الموبايل يشغّل صفحة على الشبكة المحلية والـ iPad يفتحها في Safari
 */
class ScreenService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_DATA = "data"
        const val EXTRA_CODE = "code"
        const val EXTRA_NAME = "name"
        const val EXTRA_MODE = "mode"
        const val EXTRA_MIC = "mic"
        const val PORT = 8080
        private const val NOTIF_ID = 7
        private const val CHANNEL = "screen"

        @Volatile var running = false
        @Volatile var mode = ""
        @Volatile var status = ""
        var listener: ((String, Boolean) -> Unit)? = null

        fun localAddresses(): List<String> {
            val out = ArrayList<Pair<Int, String>>()
            try {
                for (ni in NetworkInterface.getNetworkInterfaces()) {
                    if (!ni.isUp || ni.isLoopback) continue
                    val n = ni.name.lowercase()
                    if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("dummy") || n.startsWith("tun")) continue
                    for (a in ni.inetAddresses) {
                        if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) {
                            val ip = a.hostAddress ?: continue
                            val prio = if (n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan")) 0 else 1
                            out.add(Pair(prio, ip))
                        }
                    }
                }
            } catch (_: Exception) {}
            return out.sortedBy { it.first }.map { it.second }.distinct()
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var stopped = false
    private var micOn = false

    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var helper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var pc: PeerConnection? = null
    private var capW = 0
    private var capH = 0

    // صوت الموبايل الداخلي
    @Volatile private var innerRec: AudioRecord? = null
    @Volatile private var innerFailed = false
    private var innerBuf: ByteBuffer? = null

    // وضع الإنترنت
    private var ws: WebSocket? = null
    private var http: OkHttpClient? = null
    private var target = ""
    private var connId = ""
    private var name = ""
    private var remoteSet = false
    private val pendingCandidates = ArrayList<IceCandidate>()
    private var connected = false

    // الوضع المحلي
    private var server: ServerSocket? = null
    private var gatherLatch: CountDownLatch? = null
    private var viewerId = 0

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
        mode = intent?.getStringExtra(EXTRA_MODE) ?: "net"
        micOn = intent?.getBooleanExtra(EXTRA_MIC, false) ?: false
        if (data == null || (mode == "net" && code.isEmpty())) { stopSelf(); return START_NOT_STICKY }
        running = true
        stopped = false
        goForeground("جارٍ التحضير...")
        try {
            startEngine(data)
            if (mode == "local") startLocal() else startNet(code)
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
        return b.setContentTitle(if (mode == "local") "عرض محلي بدون نت" else "عرض الشاشة على الـ iPad")
            .setContentText(text.lines().firstOrNull() ?: text)
            .setStyle(Notification.BigTextStyle().bigText(text))
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

    private fun setStatus(text: String) {
        status = text
        main.post {
            listener?.invoke(text, true)
            if (running) try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(text)) } catch (_: Exception) {}
        }
    }

    // ===== المحرك: التقاط الشاشة والصوت =====
    private fun startEngine(data: Intent) {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions()
        )
        val e = EglBase.create()
        egl = e
        val adm = JavaAudioDeviceModule.builder(this)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setUseStereoInput(true)
            .setAudioRecordDataCallback { _, channelCount, sampleRate, audioBuffer ->
                mixInnerAudio(channelCount, sampleRate, audioBuffer)
            }
            .createAudioDeviceModule()
        val f = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(e.eglBaseContext, true, false))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(e.eglBaseContext))
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()
        factory = f

        val cap = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopAll("تم إيقاف عرض الشاشة") } }
        })
        capturer = cap
        val h = SurfaceTextureHelper.create("capture", e.eglBaseContext)
        helper = h
        // false = محتوى متحرك (فيديو/ماتش): الأولوية للنعومة مش للصورة الثابتة
        val vs = f.createVideoSource(false)
        videoSource = vs
        cap.initialize(h, this, vs.capturerObserver)
        val (w, hh) = captureSize()
        capW = w; capH = hh
        cap.startCapture(w, hh, 30)
        videoTrack = f.createVideoTrack("screen0", vs)

        if (hasMic()) {
            // بنقفل معالجة الصوت (إلغاء الصدى والضوضاء) عشان صوت الماتش/المزيكا يوصل نقي
            val mc = MediaConstraints().apply {
                for (k in listOf("googEchoCancellation", "googAutoGainControl", "googNoiseSuppression",
                    "googHighpassFilter", "googTypingNoiseDetection", "googEchoCancellation2", "googAutoGainControl2", "googNoiseSuppression2"))
                    mandatory.add(MediaConstraints.KeyValuePair(k, "false"))
            }
            val asrc = f.createAudioSource(mc)
            audioSource = asrc
            audioTrack = f.createAudioTrack("audio0", asrc)
        }
    }

    private fun captureSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        val w = dm.widthPixels.toDouble()
        val h = dm.heightPixels.toDouble()
        // أقصى حاجة 1080p (1920×1080) — ده أعلى ما يقدر الـ iPad يشغّله بالمعالج بتاعه
        val sc = min(1.0, min(1920.0 / max(w, h), 1080.0 / min(w, h)))
        return Pair((w * sc).toInt() and 1.inv(), (h * sc).toInt() and 1.inv())
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        main.postDelayed({
            val cap = capturer ?: return@postDelayed
            val (w, h) = captureSize()
            if (w != capW || h != capH) {
                capW = w; capH = h
                try { cap.changeCaptureFormat(w, h, 30) } catch (_: Exception) {}
            }
        }, 300)
    }

    /** بيتنادى كل 10 ملي ثانية بصوت المايك: بنحط مكانه (أو نخلطه مع) صوت الموبايل الداخلي */
    private fun mixInnerAudio(channels: Int, sampleRate: Int, buf: ByteBuffer) {
        val size = buf.remaining()
        if (innerRec == null && !innerFailed) startInnerRecord(channels, sampleRate)
        val rec = innerRec
        val start = buf.position()
        val out = buf.duplicate().order(ByteOrder.nativeOrder())
        if (rec == null) {
            if (!micOn) for (i in 0 until size) buf.put(start + i, 0)
            return
        }
        var ib = innerBuf
        if (ib == null || ib.capacity() < size) { ib = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder()); innerBuf = ib }
        ib!!.clear()
        val n = try { rec.read(ib, size, AudioRecord.READ_NON_BLOCKING) } catch (_: Exception) { 0 }
        val got = max(0, n)
        var i = 0
        while (i + 1 < size) {
            val inner = if (i + 1 < got) ib.getShort(i).toInt() else 0
            val mic = if (micOn) out.getShort(start + i).toInt() else 0
            val v = (inner + mic).coerceIn(-32768, 32767)
            out.putShort(start + i, v.toShort())
            i += 2
        }
    }

    private fun startInnerRecord(channels: Int, sampleRate: Int) {
        if (Build.VERSION.SDK_INT < 29 || !hasMic()) { innerFailed = true; return }
        try {
            val cap = capturer ?: return
            val mp = cap.mediaProjection ?: return
            val conf = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(sampleRate,
                if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            @Suppress("MissingPermission")
            val r = AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(max(minBuf * 2, sampleRate * channels * 2 / 5))
                .setAudioPlaybackCaptureConfig(conf)
                .build()
            r.startRecording()
            innerRec = r
        } catch (_: Exception) {
            innerFailed = true
        }
    }

    // ===== تعديلات SDP: H.264 أولاً، وصوت ستيريو عالي الجودة، وبداية بسرعة عالية =====
    private fun preferH264(sdp: String): String {
        val lines = sdp.split("\r\n").toMutableList()
        val mIdx = lines.indexOfFirst { it.startsWith("m=video") }
        if (mIdx < 0) return sdp
        val h264 = ArrayList<String>()
        for (l in lines) {
            val m = Regex("^a=rtpmap:(\\d+) H264/").find(l) ?: continue
            h264.add(m.groupValues[1])
        }
        if (h264.isEmpty()) return sdp
        // نفضّل constrained baseline (42e0) لأنه اللي الـ iPad القديم بيفكه بالمعالج
        val baseline = h264.filter { pt -> lines.any { it.startsWith("a=fmtp:$pt ") && it.contains("profile-level-id=42e0") } }
        val order = baseline + h264.filter { it !in baseline }
        val parts = lines[mIdx].split(" ")
        val pts = parts.drop(3)
        lines[mIdx] = (parts.take(3) + order + pts.filter { it !in order }).joinToString(" ")
        return lines.joinToString("\r\n")
    }

    private fun tuneAnswer(sdp: String, maxKbps: Int): String {
        val lines = sdp.split("\r\n").toMutableList()
        val opus = lines.mapNotNull { Regex("^a=rtpmap:(\\d+) opus/").find(it)?.groupValues?.get(1) }.firstOrNull()
        val vids = lines.mapNotNull { Regex("^a=rtpmap:(\\d+) (H264|VP8|VP9)/").find(it)?.groupValues?.get(1) }
        for (i in lines.indices) {
            val l = lines[i]
            if (opus != null && l.startsWith("a=fmtp:$opus ") && !l.contains("maxaveragebitrate"))
                lines[i] = "$l;stereo=1;sprop-stereo=1;maxaveragebitrate=192000"
            for (pt in vids) if (l.startsWith("a=fmtp:$pt ") && !l.contains("x-google"))
                lines[i] = "$l;x-google-start-bitrate=${maxKbps / 2};x-google-min-bitrate=800;x-google-max-bitrate=$maxKbps"
        }
        return lines.joinToString("\r\n")
    }

    private fun applyBitrate(p: PeerConnection, maxKbps: Int) {
        try {
            for (s in p.senders) {
                val t = s.track() ?: continue
                if (t.kind() != MediaStreamTrack.VIDEO_TRACK_KIND) continue
                val params = s.parameters
                params.degradationPreference = RtpParameters.DegradationPreference.BALANCED
                for (enc in params.encodings) {
                    enc.maxBitrateBps = maxKbps * 1000
                    enc.minBitrateBps = 800 * 1000
                    enc.maxFramerate = 30
                }
                s.parameters = params
            }
        } catch (_: Exception) {}
    }

    private fun newPeer(ice: List<PeerConnection.IceServer>, obs: PeerConnection.Observer): PeerConnection {
        val f = factory ?: throw IllegalStateException("engine")
        val cfg = PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
        }
        val p = f.createPeerConnection(cfg, obs) ?: throw IllegalStateException("تعذّر إنشاء الاتصال")
        p.addTrack(videoTrack, listOf("s0"))
        val at = audioTrack
        if (at != null) p.addTrack(at, listOf("s0"))
        else p.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
        return p
    }

    private fun randStr(n: Int): String {
        val c = "abcdefghijkmnpqrstuvwxyz23456789"
        return (1..n).map { c[Random.nextInt(c.length)] }.joinToString("")
    }

    // =====================================================================
    // وضع الإنترنت (PeerJS → موقع مكالمات البيت)
    // =====================================================================
    private fun startNet(code: String) {
        val base = "baytcall-$code"
        target = "$base-home"
        val myId = "$base-scr-" + randStr(8)
        connId = "mc_" + randStr(12)
        val ice = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        pc = newPeer(ice, netObserver)
        setStatus("جارٍ الاتصال بالخادم...")
        val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
        http = client
        val url = "wss://0.peerjs.com/peerjs?key=peerjs&id=$myId&token=${randStr(10)}&version=1.4.7"
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { main.post { onServerMessage(text) } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.post { if (running && !connected) stopAll("تعذّر الاتصال بالخادم. تأكد من الإنترنت.") }
            }
        })
        main.postDelayed({ if (running && !connected) stopAll("لم يتم الرد من الـ iPad") }, 60000)
    }

    private fun sendServer(type: String, payload: JSONObject) {
        ws?.send(JSONObject().put("type", type).put("payload", payload).put("dst", target).toString())
    }

    private fun onServerMessage(text: String) {
        if (!running) return
        val m = try { JSONObject(text) } catch (_: Exception) { return }
        val payload = m.optJSONObject("payload")
        when (m.optString("type")) {
            "OPEN" -> {
                main.removeCallbacks(heartbeat); main.postDelayed(heartbeat, 5000)
                setStatus("جارٍ الاتصال بالـ iPad...")
                makeNetOffer()
            }
            "ANSWER" -> {
                if (payload == null || payload.optString("connectionId") != connId) return
                val sdp = payload.optJSONObject("sdp")?.optString("sdp") ?: return
                val p = pc ?: return
                p.setRemoteDescription(object : SdpAdapter() {
                    override fun onSetSuccess() {
                        main.post {
                            remoteSet = true
                            pendingCandidates.forEach { pc?.addIceCandidate(it) }
                            pendingCandidates.clear()
                            applyBitrate(p, 4500)
                            if (!connected) setStatus("الـ iPad رد... جارٍ الربط")
                        }
                    }
                }, SessionDescription(SessionDescription.Type.ANSWER, tuneAnswer(sdp, 4500)))
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

    private fun makeNetOffer() {
        val p = pc ?: return
        p.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                val d = SessionDescription(desc.type, preferH264(desc.description))
                p.setLocalDescription(object : SdpAdapter() {
                    override fun onSetSuccess() {
                        main.post {
                            val meta = JSONObject().put("type", "screen").put("from", "scr").put("name", name)
                            val payload = JSONObject()
                                .put("sdp", JSONObject().put("type", "offer").put("sdp", d.description))
                                .put("type", "media").put("connectionId", connId)
                                .put("metadata", meta).put("browser", "chrome")
                            sendServer("OFFER", payload)
                            setStatus("في انتظار الرد على الـ iPad...")
                        }
                    }
                }, d)
            }
            override fun onCreateFailure(error: String?) { main.post { stopAll("تعذّر بدء الاتصال") } }
        }, MediaConstraints())
    }

    private val netObserver = object : PeerObserver() {
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
                        setStatus("✓ شاشتك معروضة على الـ iPad الآن" + audioNote())
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        setStatus("الاتصال ضعيف...")
                        main.postDelayed({
                            if (running && pc?.iceConnectionState() == PeerConnection.IceConnectionState.DISCONNECTED)
                                stopAll("انتهى العرض (الـ iPad قفل المكالمة)")
                        }, 6000)
                    }
                    PeerConnection.IceConnectionState.FAILED -> stopAll(if (connected) "انقطع الاتصال" else "تعذّر الربط بين الجهازين")
                    PeerConnection.IceConnectionState.CLOSED -> stopAll("انتهى العرض")
                    else -> {}
                }
            }
        }
    }

    private fun audioNote(): String = when {
        !hasMic() -> "\n(بدون صوت: لازم تسمح بإذن تسجيل الصوت)"
        Build.VERSION.SDK_INT < 29 -> "\n(صوت الموبايل الداخلي محتاج أندرويد 10 أو أحدث)"
        innerFailed -> "\n(تعذّر التقاط صوت الموبايل الداخلي)"
        else -> ""
    }

    // =====================================================================
    // الوضع المحلي بدون إنترنت (الموبايل نفسه هو السيرفر)
    // =====================================================================
    private fun startLocal() {
        val ss = ServerSocket(PORT)
        ss.reuseAddress = true
        server = ss
        Thread {
            while (running) {
                val s = try { ss.accept() } catch (_: Exception) { break }
                Thread { handle(s) }.start()
            }
        }.start()
        setStatus(localAddressText())
    }

    private fun localAddressText(): String {
        val ips = localAddresses()
        if (ips.isEmpty()) return "مش متصل بواي فاي ولا مشغّل نقطة اتصال.\nشغّل نقطة الاتصال (Hotspot) ووصّل بيها الـ iPad، وبعدين أوقف العرض وشغّله تاني."
        val sb = StringBuilder("افتح Safari على الـ iPad واكتب:\n")
        ips.forEach { sb.append("\n").append(it).append(":").append(PORT) }
        return sb.toString()
    }

    private val localObserver = object : PeerObserver() {
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {
            if (s == PeerConnection.IceGatheringState.COMPLETE) gatherLatch?.countDown()
        }
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            main.post {
                if (!running) return@post
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED ->
                        setStatus("✓ شاشتك معروضة على الـ iPad (بدون نت)" + audioNote())
                    PeerConnection.IceConnectionState.FAILED, PeerConnection.IceConnectionState.DISCONNECTED ->
                        setStatus("الـ iPad قطع الاتصال.\n\n" + localAddressText())
                    else -> {}
                }
            }
        }
    }

    /** ينفّذ كود على الخيط الرئيسي ويستنى النتيجة */
    private fun <T> onMain(timeoutMs: Long, block: ((T?) -> Unit) -> Unit): T? {
        val latch = CountDownLatch(1)
        var result: T? = null
        main.post { block { r -> result = r; latch.countDown() } }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return result
    }

    /** الـ iPad طلب عرض جديد: نجهّز اتصال ونرجّع العرض (offer) كامل بعناوينه */
    private fun localOffer(): String? {
        return onMain<String>(6000) { done ->
            try {
                pc?.let { try { it.close(); it.dispose() } catch (_: Exception) {} }
                val p = newPeer(emptyList(), localObserver)
                pc = p
                viewerId++
                val latch = CountDownLatch(1)
                gatherLatch = latch
                p.createOffer(object : SdpAdapter() {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        val d = SessionDescription(desc.type, preferH264(desc.description))
                        p.setLocalDescription(object : SdpAdapter() {
                            override fun onSetSuccess() {
                                Thread {
                                    latch.await(2500, TimeUnit.MILLISECONDS)
                                    main.post {
                                        val sdp = p.localDescription?.description
                                        done(if (sdp == null) null else JSONObject().put("id", viewerId).put("sdp", sdp).toString())
                                    }
                                }.start()
                            }
                            override fun onSetFailure(error: String?) { main.post { done(null) } }
                        }, d)
                    }
                    override fun onCreateFailure(error: String?) { main.post { done(null) } }
                }, MediaConstraints())
            } catch (_: Exception) { done(null) }
        }
    }

    private fun localAnswer(body: String): Boolean {
        val j = try { JSONObject(body) } catch (_: Exception) { return false }
        val sdp = j.optString("sdp")
        if (sdp.isEmpty() || j.optInt("id") != viewerId) return false
        return onMain<Boolean>(5000) { done ->
            val p = pc
            if (p == null) done(false)
            else p.setRemoteDescription(object : SdpAdapter() {
                override fun onSetSuccess() { main.post { applyBitrate(p, 8000); done(true) } }
                override fun onSetFailure(error: String?) { main.post { done(false) } }
            }, SessionDescription(SessionDescription.Type.ANSWER, tuneAnswer(sdp, 8000)))
        } ?: false
    }

    private fun handle(s: Socket) {
        try {
            s.tcpNoDelay = true
            s.soTimeout = 10000
            val inp = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val line = inp.readLine() ?: return
            var len = 0
            while (true) {
                val l = inp.readLine() ?: break
                if (l.isEmpty()) break
                if (l.lowercase().startsWith("content-length:")) len = l.substring(15).trim().toIntOrNull() ?: 0
            }
            val path = line.split(" ").getOrNull(1) ?: "/"
            val out = s.getOutputStream()
            when {
                path.startsWith("/offer") -> {
                    val r = localOffer()
                    if (r == null) send(out, "503 Service Unavailable", "text/plain", "busy".toByteArray())
                    else send(out, "200 OK", "application/json", r.toByteArray())
                }
                path.startsWith("/answer") -> {
                    val buf = CharArray(len)
                    var got = 0
                    while (got < len) { val n = inp.read(buf, got, len - got); if (n < 0) break; got += n }
                    val ok = localAnswer(String(buf, 0, got))
                    send(out, if (ok) "200 OK" else "409 Conflict", "text/plain", (if (ok) "ok" else "no").toByteArray())
                }
                path == "/" || path.startsWith("/?") -> send(out, "200 OK", "text/html; charset=utf-8", LOCAL_PAGE.toByteArray())
                else -> send(out, "404 Not Found", "text/plain", "not found".toByteArray())
            }
        } catch (_: Exception) {
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }

    private fun send(out: OutputStream, code: String, type: String, body: ByteArray) {
        out.write(("HTTP/1.1 $code\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body)
        out.flush()
    }

    // ===== الإيقاف =====
    private fun stopAll(msg: String) {
        if (stopped) return
        stopped = true
        running = false
        main.removeCallbacksAndMessages(null)
        try { server?.close() } catch (_: Exception) {}
        server = null
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
        try { innerRec?.stop(); innerRec?.release() } catch (_: Exception) {}
        innerRec = null
        try { capturer?.stopCapture() } catch (_: Exception) {}
        try { capturer?.dispose() } catch (_: Exception) {}
        try { pc?.close(); pc?.dispose() } catch (_: Exception) {}
        try { videoSource?.dispose() } catch (_: Exception) {}
        try { audioSource?.dispose() } catch (_: Exception) {}
        try { helper?.dispose() } catch (_: Exception) {}
        try { factory?.dispose() } catch (_: Exception) {}
        try { egl?.release() } catch (_: Exception) {}
        capturer = null; pc = null; videoSource = null; audioSource = null; helper = null; factory = null; egl = null
        try { http?.dispatcher?.executorService?.shutdown() } catch (_: Exception) {}
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

open class PeerObserver : PeerConnection.Observer {
    override fun onIceCandidate(c: IceCandidate) {}
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
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

// صفحة العرض اللي بتفتح على الـ iPad في الوضع المحلي (متوافقة مع Safari في iOS 12)
private const val LOCAL_PAGE = """<!doctype html>
<html lang="ar" dir="rtl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<title>شاشة الموبايل</title>
<style>
html,body{margin:0;height:100%;background:#000;overflow:hidden;font-family:-apple-system,Tahoma,sans-serif;color:#fff}
#v{position:fixed;top:0;left:0;width:100%;height:100%;object-fit:contain;background:#000}
body.fill #v{object-fit:cover}
body.r90 #v,body.r270 #v{top:50%;left:50%;width:100vh;height:100vw}
body.r90 #v{-webkit-transform:translate(-50%,-50%) rotate(90deg);transform:translate(-50%,-50%) rotate(90deg)}
body.r270 #v{-webkit-transform:translate(-50%,-50%) rotate(-90deg);transform:translate(-50%,-50%) rotate(-90deg)}
body.r180 #v{-webkit-transform:rotate(180deg);transform:rotate(180deg)}
#bar{position:fixed;left:0;right:0;bottom:22px;text-align:center;z-index:5;-webkit-transition:opacity .4s;transition:opacity .4s}
#bar.hide{opacity:0}
#bar button{width:64px;height:64px;border-radius:50%;border:0;font-size:26px;margin:0 8px;background:rgba(255,255,255,.22);color:#fff}
#bar button.on{background:#fff;color:#111}
#cover{position:fixed;top:0;left:0;right:0;bottom:0;z-index:9;background:#0f172a;display:-webkit-box;display:flex;-webkit-box-orient:vertical;flex-direction:column;-webkit-box-align:center;align-items:center;-webkit-box-pack:center;justify-content:center;text-align:center;padding:20px}
#cover h1{font-size:26px;margin:0 0 10px}
#cover p{color:#94a3b8;font-size:17px;line-height:1.7;margin:0 0 20px;max-width:460px}
#go{border:0;border-radius:16px;padding:18px 40px;font-size:22px;font-weight:600;color:#fff;background:#22c55e}
#msg{position:fixed;top:16px;left:16px;right:16px;text-align:center;font-size:17px;z-index:6;text-shadow:0 1px 4px #000}
</style></head><body>
<video id="v" playsinline autoplay></video>
<div id="msg"></div>
<div id="bar" class="hide"><button id="rot">🔄</button><button id="fill">⛶</button></div>
<div id="cover"><div style="font-size:64px">📺</div><h1>شاشة الموبايل</h1><p id="cmsg">اضغط الزر عشان تبدأ العرض بالصوت</p><button id="go">▶ ابدأ العرض</button></div>
<script>
(function(){
  function $(i){return document.getElementById(i);}
  function g(k,d){try{var v=localStorage.getItem(k);return v===null?d:JSON.parse(v);}catch(e){return d;}}
  function s(k,v){try{localStorage.setItem(k,JSON.stringify(v));}catch(e){}}
  var rot=g('rot',0),fill=g('fill',false);
  function view(){document.body.className=(rot?'r'+rot:'')+(fill?' fill':'');$('fill').className=fill?'on':'';}
  view();
  $('rot').onclick=function(e){e.stopPropagation();rot=(rot+90)%360;s('rot',rot);view();showBar();};
  $('fill').onclick=function(e){e.stopPropagation();fill=!fill;s('fill',fill);view();showBar();};
  var bt=null;
  function showBar(){$('bar').className='';clearTimeout(bt);bt=setTimeout(function(){$('bar').className='hide';},4000);}
  document.body.onclick=function(){showBar();var p=$('v').play();if(p&&p.catch)p.catch(function(){});};
  function msg(t){$('msg').textContent=t||'';}
  function req(method,url,body,cb){
    var x=new XMLHttpRequest();x.open(method,url,true);x.timeout=10000;
    x.onload=function(){cb(x.status===200?x.responseText:null);};
    x.onerror=x.ontimeout=function(){cb(null);};
    x.send(body||null);
  }
  if(!window.RTCPeerConnection){$('cmsg').textContent='المتصفح ده لا يدعم العرض. افتح الصفحة من Safari.';$('go').style.display='none';return;}
  var pc=null,retryT=null,started=false;
  function retry(t){msg(t);clearTimeout(retryT);retryT=setTimeout(connect,2000);}
  function connect(){
    clearTimeout(retryT);
    if(pc){try{pc.close();}catch(e){}pc=null;}
    msg('جارٍ الاتصال بالموبايل...');
    req('GET','/offer?t='+new Date().getTime(),null,function(r){
      if(!r)return retry('الموبايل مش بيرد... تأكد إن العرض شغال وإن الجهازين على نفس الشبكة');
      var o;try{o=JSON.parse(r);}catch(e){return retry('خطأ، إعادة المحاولة...');}
      var p=new RTCPeerConnection({iceServers:[]});pc=p;
      p.ontrack=function(ev){
        var v=$('v');
        var st=(ev.streams&&ev.streams[0])||null;
        if(!st){st=v.srcObject||new MediaStream();st.addTrack(ev.track);}
        if(v.srcObject!==st)v.srcObject=st;
        var pl=v.play();if(pl&&pl.catch)pl.catch(function(){msg('المس الشاشة لتشغيل الصوت');});
      };
      p.oniceconnectionstatechange=function(){
        if(pc!==p)return;
        var st=p.iceConnectionState;
        if(st==='connected'||st==='completed')msg('');
        else if(st==='failed')retry('انقطع الاتصال... إعادة المحاولة');
        else if(st==='disconnected')setTimeout(function(){if(pc===p&&p.iceConnectionState==='disconnected')retry('انقطع الاتصال... إعادة المحاولة');},4000);
      };
      p.setRemoteDescription({type:'offer',sdp:o.sdp}).then(function(){return p.createAnswer();}).then(function(a){return p.setLocalDescription(a);}).then(function(){
        return new Promise(function(res){
          if(p.iceGatheringState==='complete')return res();
          var t=setTimeout(res,1500);
          p.onicegatheringstatechange=function(){if(p.iceGatheringState==='complete'){clearTimeout(t);res();}};
        });
      }).then(function(){
        req('POST','/answer',JSON.stringify({id:o.id,sdp:p.localDescription.sdp}),function(ok){if(!ok)retry('إعادة المحاولة...');});
      }).catch(function(){retry('خطأ في الاتصال، إعادة المحاولة...');});
    });
  }
  $('go').onclick=function(){
    if(started)return;started=true;
    $('cover').style.display='none';
    var v=$('v');var pl=v.play();if(pl&&pl.catch)pl.catch(function(){});
    showBar();connect();
  };
})();
</script></body></html>"""
