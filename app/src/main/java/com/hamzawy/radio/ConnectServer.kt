package com.hamzawy.radio

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * A tiny web server that runs only while the "connect phone" dialog is open.
 * The phone opens http://<tv-ip>:<port>/<token>/ in its browser and can send a theme picture
 * (already shrunk by the page) or add a radio station. The random token keeps other devices
 * on the network from sending things by accident.
 */
class ConnectServer(
    private val token: String,
    private val allowImage: Boolean,
    private val allowStation: Boolean,
    private val listener: Listener
) {

    interface Listener {
        /** Called on a background thread. */
        fun onImage(bytes: ByteArray)

        /** Called on a background thread. */
        fun onStation(name: String, url: String)
    }

    @Volatile
    private var socket: ServerSocket? = null

    @Volatile
    private var running = false

    var port: Int = 0
        private set

    fun start(): Boolean {
        val ss = openSocket() ?: return false
        socket = ss
        port = ss.localPort
        running = true
        val t = Thread { acceptLoop(ss) }
        t.isDaemon = true
        t.start()
        return true
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (e: IOException) {
            // Nothing to do.
        }
        socket = null
    }

    private fun openSocket(): ServerSocket? {
        for (p in 8080..8090) {
            try {
                return ServerSocket(p)
            } catch (e: IOException) {
                // Port busy, try the next one.
            }
        }
        return try {
            ServerSocket(0)
        } catch (e: IOException) {
            null
        }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            try {
                val client = ss.accept()
                val t = Thread { handle(client) }
                t.isDaemon = true
                t.start()
            } catch (e: IOException) {
                break
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 20_000
            val input = BufferedInputStream(client.getInputStream())
            val out = client.getOutputStream()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore('?')
            var length = 0
            while (true) {
                val header = readLine(input) ?: break
                if (header.isEmpty()) break
                val idx = header.indexOf(':')
                if (idx > 0 && header.substring(0, idx).trim().equals("content-length", ignoreCase = true)) {
                    length = header.substring(idx + 1).trim().toIntOrNull() ?: 0
                }
            }
            route(method, path, length, input, out)
        } catch (e: Exception) {
            // A dropped connection is not an error worth reporting.
        } finally {
            try {
                client.close()
            } catch (e: IOException) {
                // Ignore.
            }
        }
    }

    private fun route(method: String, path: String, length: Int, input: InputStream, out: OutputStream) {
        val base = "/$token"
        if (method == "GET" && (path == base || path == "$base/")) {
            send(out, 200, "text/html", page())
        } else if (method == "POST" && path == "$base/image" && allowImage) {
            if (length <= 0 || length > MAX_IMAGE_BYTES) {
                send(out, 413, "text/plain", "الصورة كبيرة قوي، جرّب صورة أصغر")
                return
            }
            listener.onImage(readBody(input, length))
            send(out, 200, "text/plain", "تم الإرسال، بص على التلفزيون ✓")
        } else if (method == "POST" && path == "$base/station" && allowStation) {
            if (length <= 0 || length > 8192) {
                send(out, 400, "text/plain", "الطلب مش صحيح")
                return
            }
            val json = JSONObject(String(readBody(input, length), Charsets.UTF_8))
            val name = json.optString("name").trim()
            val url = json.optString("url").trim()
            if (name.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) {
                send(out, 400, "text/plain", "الاسم أو الرابط مش صحيح (الرابط لازم يبدأ بـ http)")
                return
            }
            listener.onStation(name, url)
            send(out, 200, "text/plain", "تمت إضافة المحطة ✓")
        } else {
            send(out, 404, "text/plain", "Not found")
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
        return sb.toString()
    }

    private fun readBody(input: InputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = input.read(buf, off, length - off)
            if (n < 0) throw IOException("connection closed")
            off += n
        }
        return buf
    }

    private fun send(out: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            413 -> "Payload Too Large"
            else -> "Not Found"
        }
        val head = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $type; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun page(): String {
        val sb = StringBuilder()
        sb.append(HEAD)
        if (allowImage) sb.append(IMAGE_SECTION)
        if (allowStation) sb.append(STATION_SECTION)
        sb.append("<script>")
        if (allowImage) sb.append(IMAGE_JS)
        if (allowStation) sb.append(STATION_JS)
        sb.append("</script></body></html>")
        return sb.toString().replace("__T__", token)
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 12 * 1024 * 1024

        const val HEAD = """<!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Hamzawy Radio</title>
<style>
body{margin:0;padding:22px;background:#0e1626;color:#fff;font-family:sans-serif;text-align:center}
h2{margin:6px 0 4px}p{color:#b8c2d6;margin:4px 0 12px}
.card{max-width:460px;margin:0 auto 22px;padding:18px;background:#16223a;border-radius:18px}
button{display:block;width:100%;box-sizing:border-box;padding:16px;margin:10px 0;border:0;border-radius:14px;background:#35d0ba;color:#04201c;font-size:19px;font-weight:bold}
input[type=text]{width:100%;box-sizing:border-box;padding:14px;margin:6px 0;border-radius:12px;border:1px solid #2c3d5e;background:#0e1626;color:#fff;font-size:17px}
img{display:none;max-width:100%;border-radius:12px;margin-top:10px}
.msg{min-height:22px;font-weight:bold;color:#35d0ba}
</style></head><body>
<h2>Hamzawy Radio</h2>
"""

        const val IMAGE_SECTION = """<div class="card"><h3>صورة الثيم</h3><p>اختار صورة من الموبايل وهتبقى خلفية التطبيق على التلفزيون</p>
<input id="f" type="file" accept="image/*" style="display:none">
<button onclick="document.getElementById('f').click()">اختيار صورة من الموبايل</button>
<img id="p"><button id="s" style="display:none" onclick="sendImg()">إرسال للتلفزيون</button>
<div class="msg" id="m1"></div></div>
"""

        const val STATION_SECTION = """<div class="card"><h3>إضافة محطة راديو</h3><p>اكتب اسم المحطة ورابط البث المباشر بتاعها</p>
<input id="n" type="text" placeholder="اسم المحطة">
<input id="u" type="text" placeholder="https://..." dir="ltr">
<button onclick="sendSt()">إضافة المحطة</button><div class="msg" id="m2"></div></div>
"""

        const val IMAGE_JS = """
var blob=null;
document.getElementById('f').onchange=function(e){
 var file=e.target.files[0]; if(!file)return;
 var img=new Image(); var url=URL.createObjectURL(file);
 img.onload=function(){
  var max=1920,w=img.naturalWidth,h=img.naturalHeight,k=Math.min(1,max/Math.max(w,h));
  var c=document.createElement('canvas'); c.width=Math.round(w*k); c.height=Math.round(h*k);
  c.getContext('2d').drawImage(img,0,0,c.width,c.height);
  c.toBlob(function(b){blob=b;var p=document.getElementById('p');p.src=URL.createObjectURL(b);p.style.display='block';document.getElementById('s').style.display='block';},'image/jpeg',0.9);
 };
 img.src=url;
};
function sendImg(){
 if(!blob)return; var m=document.getElementById('m1'); m.textContent='جاري الإرسال...';
 fetch('/__T__/image',{method:'POST',headers:{'Content-Type':'image/jpeg'},body:blob})
  .then(function(r){return r.text();}).then(function(t){m.textContent=t;})
  .catch(function(){m.textContent='حصلت مشكلة، جرّب تاني';});
}
"""

        const val STATION_JS = """
function sendSt(){
 var n=document.getElementById('n').value.trim(),u=document.getElementById('u').value.trim();
 var m=document.getElementById('m2');
 if(!n||!u){m.textContent='اكتب الاسم والرابط';return;}
 fetch('/__T__/station',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({name:n,url:u})})
  .then(function(r){return r.text();}).then(function(t){m.textContent=t;})
  .catch(function(){m.textContent='حصلت مشكلة، جرّب تاني';});
}
"""
    }
}
