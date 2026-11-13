package com.ricardochr.estantepinturas

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Ilumina una foto en la GPU: el relieve (normales, huecos, alturas) va en texturas a la resolución del
 * modelo de profundidad y la foto, el recorte y el detalle a resolución completa. Las sombras se
 * calculan en el sombreador recorriendo el relieve hacia la luz, así mover el sol es fluido.
 */
class PhotoGlRenderer(private val requestRender: () -> Unit) : GLSurfaceView.Renderer {
    @Volatile var data: PhotoGpuData? = null
        set(v) { field = v; requestRender() }
    @Volatile var light = LightParams()
        set(v) { field = v; requestRender() }
    @Volatile var side = PhotoSide.FRENTE
    @Volatile var look = PhotoLook.ARCILLA
    @Volatile var detailAmount = 0.7f
    @Volatile var showOriginal = false
    @Volatile var captureNext: ((Bitmap) -> Unit)? = null

    fun changed() = requestRender()

    private var uploaded: PhotoGpuData? = null
    private var prog = 0
    private val tex = IntArray(4)
    private var vbo = 0
    private var width = 1
    private var height = 1

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        uploaded = null
        prog = program(VS, FS)
        GLES30.glGenTextures(4, tex, 0)
        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val fb = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(quad)
        fb.position(0)
        val b = IntArray(1)
        GLES30.glGenBuffers(1, b, 0)
        vbo = b[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, fb, GLES30.GL_STATIC_DRAW)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w.coerceAtLeast(1)
        height = h.coerceAtLeast(1)
    }

    private fun upload(d: PhotoGpuData) {
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        fun setup(t: Int, linear: Boolean) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
            val f = if (linear) GLES30.GL_LINEAR else GLES30.GL_NEAREST
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, f)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, f)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }
        fun bytes(a: ByteArray) = ByteBuffer.allocateDirect(a.size).order(ByteOrder.nativeOrder()).put(a).also { it.position(0) }
        setup(tex[0], true)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, d.photoW, d.photoH, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes(d.photoRgba))
        setup(tex[1], true)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, d.w, d.h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes(d.normalAo))
        setup(tex[2], false)
        val hb = ByteBuffer.allocateDirect(d.heights.size * 4).order(ByteOrder.nativeOrder())
        hb.asFloatBuffer().put(d.heights)
        hb.position(0)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32F, d.w, d.h, 0, GLES30.GL_RED, GLES30.GL_FLOAT, hb)
        setup(tex[3], true)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, d.photoW, d.photoH, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, bytes(d.detail))
        uploaded = d
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0.11f, 0.114f, 0.133f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val d = data ?: return
        if (d !== uploaded) upload(d)
        val lp = light
        GLES30.glUseProgram(prog)
        fun u(n: String) = GLES30.glGetUniformLocation(prog, n)
        // Encajar la foto en la vista conservando su proporción
        val ia = d.photoW.toFloat() / d.photoH
        val va = width.toFloat() / height
        if (ia > va) GLES30.glUniform2f(u("uScale"), 1f, va / ia) else GLES30.glUniform2f(u("uScale"), ia / va, 1f)
        // Luz en el sistema de la cámara de esta foto
        val phi = Math.toRadians(side.yaw.toDouble())
        val c = kotlin.math.cos(phi).toFloat()
        val s = kotlin.math.sin(phi).toFloat()
        val l = lp.direction()
        GLES30.glUniform3f(u("uL"), l[0] * c - l[2] * s, l[1], l[0] * s + l[2] * c)
        val l2 = lp.direction2()
        GLES30.glUniform3f(u("uL2"), l2[0] * c - l2[2] * s, l2[1], l2[0] * s + l2[2] * c)
        GLES30.glUniform3f(u("uC2"), lp.color2[0] * lp.intensity2, lp.color2[1] * lp.intensity2, lp.color2[2] * lp.intensity2)
        GLES30.glUniform3f(u("uLC"), lp.color[0], lp.color[1], lp.color[2])
        GLES30.glUniform1f(u("uAmbient"), lp.ambient)
        GLES30.glUniform1f(u("uCavity"), lp.cavity)
        GLES30.glUniform1f(u("uDetailAmt"), detailAmount)
        GLES30.glUniform1i(u("uLook"), if (showOriginal) 2 else look.ordinal)
        GLES30.glUniform1i(u("uZones"), lp.zones)
        val th = zoneThresholds(if (lp.zones == 3) 3 else 5)
        GLES30.glUniform4f(u("uThresh"), th[0], th[1], th.getOrElse(2) { 2f }, th.getOrElse(3) { 2f })
        val zc = lp.zoneColorsFor()
        val zf = FloatArray(15)
        for (i in 0 until 5) {
            val col = zc.getOrElse(i) { zc.last() }
            zf[i * 3] = ((col shr 16) and 0xFF) / 255f; zf[i * 3 + 1] = ((col shr 8) and 0xFF) / 255f; zf[i * 3 + 2] = (col and 0xFF) / 255f
        }
        GLES30.glUniform3fv(u("uZoneColors"), 5, zf, 0)
        GLES30.glUniform2f(u("uLoSize"), d.w.toFloat(), d.h.toFloat())
        GLES30.glUniform1f(u("uHMax"), d.hMax)
        for (i in 0 until 4) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[i])
        }
        GLES30.glUniform1i(u("uPhoto"), 0)
        GLES30.glUniform1i(u("uNormal"), 1)
        GLES30.glUniform1i(u("uHeight"), 2)
        GLES30.glUniform1i(u("uDetail"), 3)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        captureNext?.let { cb ->
            captureNext = null
            val buf = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            buf.rewind()
            bmp.copyPixelsFromBuffer(buf)
            val m = android.graphics.Matrix().apply { postScale(1f, -1f) }
            val out = Bitmap.createBitmap(bmp, 0, 0, width, height, m, false)
            if (out !== bmp) bmp.recycle()
            cb(out)
        }
    }

    private fun program(vs: String, fs: String): Int {
        fun shader(type: Int, src: String): Int {
            val sh = GLES30.glCreateShader(type)
            GLES30.glShaderSource(sh, src)
            GLES30.glCompileShader(sh)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(sh, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) android.util.Log.e("Gotario", tr("Shader foto: ") + GLES30.glGetShaderInfoLog(sh))
            return sh
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        return p
    }

    private companion object {
        const val VS = """#version 300 es
layout(location = 0) in vec2 aPos;
uniform vec2 uScale;
out vec2 vUv;
void main() {
    gl_Position = vec4(aPos * uScale, 0.0, 1.0);
    vUv = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5);
}
"""
        const val FS = """#version 300 es
precision highp float;
precision highp sampler2D;
in vec2 vUv;
uniform sampler2D uPhoto;
uniform sampler2D uNormal;
uniform sampler2D uHeight;
uniform sampler2D uDetail;
uniform vec2 uLoSize;
uniform float uHMax;
uniform vec3 uL;
uniform vec3 uL2;
uniform vec3 uC2;
uniform vec3 uLC;
uniform float uAmbient;
uniform float uCavity;
uniform float uDetailAmt;
uniform int uLook;
uniform int uZones;
uniform vec4 uThresh;
uniform vec3 uZoneColors[5];
out vec4 outColor;

float heightAt(vec2 p) { return texelFetch(uHeight, ivec2(p), 0).r; }

// Sombra suave: se avanza por el relieve hacia la luz y se guarda qué tan cerca pasó el rayo de taparse
float shadowAt(vec2 uv) {
    vec2 p = uv * uLoSize;
    float h0 = heightAt(p);
    float lxy = length(uL.xy);
    if (lxy < 0.03) return 1.0;
    vec2 d = vec2(uL.x, -uL.y) / lxy;
    float slope = uL.z / lxy;
    float res = 1.0;
    float t = 1.5;
    for (int k = 0; k < 180; k++) {
        float rayH = h0 + slope * t;
        if (rayH > uHMax + 1.0) break;
        vec2 q = p + d * t;
        if (q.x < 0.0 || q.y < 0.0 || q.x >= uLoSize.x || q.y >= uLoSize.y) break;
        float diff = heightAt(q) - rayH - 0.6;
        if (diff > 0.0) return 0.0;
        res = min(res, clamp(-diff * 6.0 / t, 0.0, 1.0));
        t += t < 12.0 ? 1.0 : (t < 60.0 ? 1.5 : 3.0);
    }
    return res;
}

vec3 toLinear(vec3 c) { return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c)); }
vec3 toSrgb(vec3 c) { c = clamp(c, 0.0, 1.0); return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c)); }

void main() {
    if (vUv.x < 0.0 || vUv.y < 0.0 || vUv.x > 1.0 || vUv.y > 1.0) { outColor = vec4(0.11, 0.114, 0.133, 1.0); return; }
    vec4 ph = texture(uPhoto, vUv);
    if (ph.a < 0.5) { outColor = vec4(0.11, 0.114, 0.133, 1.0); return; }
    if (uLook == 2) { outColor = vec4(ph.rgb, 1.0); return; }
    vec4 na = texture(uNormal, vUv);
    vec3 n = normalize(na.rgb * 2.0 - 1.0);
    float ndl = dot(n, uL);
    float diff = max(ndl, 0.0);
    float sh = ndl > 0.0 ? shadowAt(vUv) : 0.0;
    float occl = clamp(1.0 - uCavity * na.a * 0.75, 0.25, 1.0);
    float sky = 0.65 + 0.35 * n.y;
    float direct = (1.0 - uAmbient) * diff * sh;
    float amb = uAmbient * occl * sky;
    vec3 fill = uC2 * max(dot(n, uL2), 0.0);
    if (uZones > 0) {
        float intensity = direct + amb * 1.4 + dot(fill, vec3(0.2126, 0.7152, 0.0722));
        int z;
        if (uZones == 3) z = intensity < uThresh.x ? 0 : (intensity < uThresh.y ? 1 : 2);
        else z = intensity < uThresh.x ? 0 : (intensity < uThresh.y ? 1 : (intensity < uThresh.z ? 2 : (intensity < uThresh.w ? 3 : 4)));
        outColor = vec4(uZoneColors[z], 1.0);
        return;
    }
    vec3 albedo;
    float k;
    if (uLook == 1) {
        albedo = toLinear(ph.rgb);
        k = 1.9;
    } else {
        float lg = texture(uDetail, vUv).r * 2.05 - 1.2;
        albedo = vec3(0.62, 0.62, 0.63) * pow(exp2(lg), uDetailAmt);
        k = 1.25;
    }
    vec3 c = albedo * (uLC * direct + vec3(amb) + fill) * k;
    c = c / (1.0 + 0.25 * c);
    outColor = vec4(toSrgb(c), 1.0);
}
"""
    }
}
