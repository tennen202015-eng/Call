package com.bayt.screen

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.math.max

/**
 * عرض محلي بدون إنترنت: الموبايل يشغّل سيرفر صغير على الشبكة المحلية (واي فاي أو نقطة اتصال)
 * والـ iPad يفتح العنوان في Safari ويشوف الشاشة.
 */
class LocalService : Service() {

    companion object {
        const val PORT = 8080
        const val ACTION_STOP = "stop"
        const val EXTRA_DATA = "data"
        private const val NOTIF_ID = 8
        private const val CHANNEL = "screen"

        @Volatile var running = false
        @Volatile var status = ""
        var listener: ((String, Boolean) -> Unit)? = null

        /** عناوين الجهاز على الشبكة المحلية (واي فاي / نقطة اتصال) */
        fun localAddresses(): List<String> {
            val out = ArrayList<Pair<Int, String>>()
            try {
                for (ni in NetworkInterface.getNetworkInterfaces()) {
                    if (!ni.isUp || ni.isLoopback) continue
                    val n = ni.name.lowercase()
                    if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("dummy") || n.startsWith("tun")) continue
                    for (a in ni.inetAddresses) {
                        if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) {
                            val prio = if (n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan")) 0 else 1
                            out.add(Pair(prio, a.hostAddress ?: continue))
                        }
                    }
                }
            } catch (_: Exception) {}
            return out.sortedBy { it.first }.map { it.second }.distinct()
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var stopped = false
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var bg: Handler? = null
    private var server: ServerSocket? = null

    private var capW = 0
    private var capH = 0
    private var dpi = 320

    private val lock = Object()
    @Volatile private var frame: ByteArray? = null
    @Volatile private var frameNo = 0L
    @Volatile private var lastRequest = 0L
    private var lastEncode = 0L
    private var bmp: Bitmap? = null
    private val jpeg = ByteArrayOutputStream(256 * 1024)
    @Volatile private var clients = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopAll("تم إيقاف العرض المحلي"); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        running = true
        stopped = false
        goForeground("جارٍ التشغيل...")
        try {
            start(data)
        } catch (e: Exception) {
            stopAll("حصل خطأ: " + (e.message ?: e.javaClass.simpleName))
        }
        return START_NOT_STICKY
    }

    private fun buildNotif(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "عرض الشاشة", NotificationManager.IMPORTANCE_LOW))
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(this, 2, Intent(this, LocalService::class.java).setAction(ACTION_STOP), flags)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("عرض محلي بدون نت")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "إيقاف", stop).build())
            .build()
    }

    private fun goForeground(text: String) {
        val n = buildNotif(text)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIF_ID, n)
    }

    private fun setStatus(text: String) {
        status = text
        main.post {
            listener?.invoke(text, true)
            if (running) try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(text)) } catch (_: Exception) {}
        }
    }

    private fun addressText(): String {
        val ips = localAddresses()
        if (ips.isEmpty()) return "مش متصل بواي فاي ولا مشغّل نقطة اتصال.\nشغّل نقطة الاتصال (Hotspot) واتصل بيها من الـ iPad، وبعدين اقفل العرض وافتحه تاني."
        val sb = StringBuilder("افتح Safari على الـ iPad واكتب:\n")
        ips.forEach { sb.append("\n").append(it).append(":").append(PORT) }
        return sb.toString()
    }

    // ===== التقاط الشاشة =====
    private fun start(data: Intent) {
        val t = HandlerThread("local-capture").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        bg = h

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(Activity.RESULT_OK, data) ?: throw IllegalStateException("تعذّر بدء تسجيل الشاشة")
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopAll("تم إيقاف العرض المحلي") } }
        }, main)

        val (w, hh) = captureSize()
        capW = w; capH = hh
        val r = newReader(w, hh)
        display = mp.createVirtualDisplay("bayt-local", w, hh, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, h)

        startServer()
        setStatus(addressText())
    }

    private fun newReader(w: Int, h: Int): ImageReader {
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ onImage(it) }, bg)
        reader = r
        return r
    }

    private fun captureSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        dpi = dm.densityDpi
        var w = dm.widthPixels
        var h = dm.heightPixels
        val sc = 1280.0 / max(w, h)
        if (sc < 1) { w = (w * sc).toInt(); h = (h * sc).toInt() }
        return Pair(w and 1.inv(), h and 1.inv())
    }

    // لف الموبايل: نغيّر أبعاد الصورة
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        main.postDelayed({
            val d = display ?: return@postDelayed
            val (w, h) = captureSize()
            if (w == capW && h == capH) return@postDelayed
            capW = w; capH = h
            bg?.post {
                try {
                    val old = reader
                    val r = newReader(w, h)
                    d.resize(w, h, dpi)
                    d.surface = r.surface
                    synchronized(lock) { bmp = null }
                    old?.close()
                } catch (_: Exception) {}
            }
        }, 300)
    }

    private fun onImage(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return
        try {
            val now = System.currentTimeMillis()
            lastEncode = now
            val plane = img.planes[0]
            val ps = plane.pixelStride
            val rs = plane.rowStride
            val fullW = rs / ps
            var b = bmp
            if (b == null || b.width != fullW || b.height != img.height) {
                b = Bitmap.createBitmap(fullW, img.height, Bitmap.Config.ARGB_8888)
                bmp = b
            }
            plane.buffer.rewind()
            b!!.copyPixelsFromBuffer(plane.buffer)
            val out = if (fullW != img.width) Bitmap.createBitmap(b, 0, 0, img.width, img.height) else b
            jpeg.reset()
            out.compress(Bitmap.CompressFormat.JPEG, 72, jpeg)
            if (out !== b) out.recycle()
            synchronized(lock) {
                frame = jpeg.toByteArray()
                frameNo++
                lock.notifyAll()
            }
        } catch (_: Exception) {
        } finally {
            img.close()
        }
    }

    // ===== السيرفر المحلي =====
    private fun startServer() {
        val ss = ServerSocket(PORT)
        ss.reuseAddress = true
        server = ss
        Thread {
            while (running) {
                val s = try { ss.accept() } catch (_: Exception) { break }
                Thread { handle(s) }.start()
            }
        }.start()
    }

    private fun handle(s: Socket) {
        try {
            s.tcpNoDelay = true
            s.soTimeout = 10000
            val inp = BufferedReader(InputStreamReader(s.getInputStream()))
            val line = inp.readLine() ?: return
            while (true) { val l = inp.readLine() ?: break; if (l.isEmpty()) break }
            val path = line.split(" ").getOrNull(1) ?: "/"
            val out = s.getOutputStream()
            when {
                path.startsWith("/snap") -> sendSnap(out)
                path.startsWith("/stream") -> sendStream(out)
                path == "/" || path.startsWith("/?") -> send(out, "200 OK", "text/html; charset=utf-8", PAGE.toByteArray())
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

    private fun waitFrame(after: Long): ByteArray? {
        lastRequest = System.currentTimeMillis()
        synchronized(lock) {
            val end = System.currentTimeMillis() + 1000
            while (running && (frame == null || frameNo <= after)) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) break
                lock.wait(left)
            }
            return frame
        }
    }

    private fun sendSnap(out: OutputStream) {
        val f = waitFrame(frameNo) ?: frame
        if (f == null) send(out, "503 Service Unavailable", "text/plain", "wait".toByteArray())
        else send(out, "200 OK", "image/jpeg", f)
    }

    private fun sendStream(out: OutputStream) {
        clients++
        try {
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
            var last = 0L
            while (running) {
                val f = waitFrame(last) ?: continue
                last = frameNo
                out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${f.size}\r\n\r\n".toByteArray())
                out.write(f)
                out.write("\r\n".toByteArray())
                out.flush()
            }
        } finally { clients-- }
    }

    // ===== الإيقاف =====
    private fun stopAll(msg: String) {
        if (stopped) return
        stopped = true
        running = false
        synchronized(lock) { lock.notifyAll() }
        try { server?.close() } catch (_: Exception) {}
        try { display?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { thread?.quitSafely() } catch (_: Exception) {}
        server = null; display = null; reader = null; projection = null; thread = null
        status = msg
        main.post { listener?.invoke(msg, false) }
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        stopAll("تم إيقاف العرض المحلي")
        super.onDestroy()
    }
}

// صفحة العرض اللي بتفتح على الـ iPad (متوافقة مع Safari القديم في iOS 12)
private const val PAGE = """<!doctype html>
<html lang="ar" dir="rtl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<meta name="apple-mobile-web-app-capable" content="yes">
<title>شاشة الموبايل</title>
<style>
html,body{margin:0;height:100%;background:#000;overflow:hidden;font-family:-apple-system,Tahoma,sans-serif}
#box{position:fixed;top:0;left:0;width:100%;height:100%}
#box img{position:absolute;top:0;left:0;width:100%;height:100%;object-fit:contain}
body.fill #box img{object-fit:cover}
body.r90 #box,body.r270 #box{top:50%;left:50%;width:100vh;height:100vw}
body.r90 #box{-webkit-transform:translate(-50%,-50%) rotate(90deg);transform:translate(-50%,-50%) rotate(90deg)}
body.r270 #box{-webkit-transform:translate(-50%,-50%) rotate(-90deg);transform:translate(-50%,-50%) rotate(-90deg)}
body.r180 #box{-webkit-transform:rotate(180deg);transform:rotate(180deg)}
#bar{position:fixed;left:0;right:0;bottom:22px;text-align:center;z-index:5;-webkit-transition:opacity .4s;transition:opacity .4s}
#bar.hide{opacity:0}
#bar button{width:64px;height:64px;border-radius:50%;border:0;font-size:26px;margin:0 8px;background:rgba(255,255,255,.22);color:#fff}
#bar button.on{background:#fff;color:#111}
#msg{position:fixed;top:40%;left:16px;right:16px;text-align:center;color:#cbd5e1;font-size:20px;z-index:4}
</style></head><body>
<div id="box"><img id="a" alt=""><img id="b" alt=""></div>
<div id="msg">جارٍ الاتصال بالموبايل...</div>
<div id="bar"><button id="rot">🔄</button><button id="fill">⛶</button></div>
<script>
(function(){
  function g(k,d){try{var v=localStorage.getItem(k);return v===null?d:JSON.parse(v);}catch(e){return d;}}
  function s(k,v){try{localStorage.setItem(k,JSON.stringify(v));}catch(e){}}
  var rot=g('rot',0),fill=g('fill',false);
  function view(){document.body.className=(rot?'r'+rot:'')+(fill?' fill':'');document.getElementById('fill').className=fill?'on':'';}
  view();
  document.getElementById('rot').onclick=function(e){e.stopPropagation();rot=(rot+90)%360;s('rot',rot);view();showBar();};
  document.getElementById('fill').onclick=function(e){e.stopPropagation();fill=!fill;s('fill',fill);view();showBar();};
  var bar=document.getElementById('bar'),bt=null;
  function showBar(){bar.className='';clearTimeout(bt);bt=setTimeout(function(){bar.className='hide';},4000);}
  document.body.onclick=showBar;showBar();
  // نحمّل الصورة الجاية في الخلفية ونبدّل لما تخلص عشان مفيش ومضة
  var imgs=[document.getElementById('a'),document.getElementById('b')],cur=0,msg=document.getElementById('msg'),fails=0;
  imgs[1].style.visibility='hidden';
  function next(){
    var n=imgs[1-cur];
    n.onload=function(){n.style.visibility='visible';imgs[cur].style.visibility='hidden';cur=1-cur;fails=0;msg.style.display='none';setTimeout(next,0);};
    n.onerror=function(){fails++;if(fails>2){msg.style.display='block';msg.textContent='الموبايل مش بيبعت... تأكد إن العرض شغال وإن الجهازين على نفس الشبكة';}setTimeout(next,800);};
    n.src='/snap.jpg?t='+(new Date().getTime());
  }
  next();
})();
</script></body></html>"""
