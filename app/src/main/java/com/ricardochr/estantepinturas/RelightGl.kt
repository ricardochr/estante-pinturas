package com.ricardochr.estantepinturas

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Parámetros de luz compartidos por la vista 3D y la de fotos. */
data class LightParams(
    /** Ángulo alrededor de la figura: 0 = desde el frente, 90 = desde la derecha de quien mira, 180 = desde atrás. */
    val azimuth: Float = 35f,
    /** Altura: 90 = cenital, 0 = horizontal, negativa = desde abajo. */
    val elevation: Float = 55f,
    /** Luz ambiental 0–1 (cuánto se ven las sombras). */
    val ambient: Float = 0.18f,
    /** Fuerza del oscurecimiento en huecos y pliegues, 0–1. */
    val cavity: Float = 0.6f,
    /** Color de la luz (RGB lineal). */
    val color: FloatArray = floatArrayOf(1f, 1f, 1f),
    /** 0 = iluminada; 3 o 5 = mapa de zonas con esa cantidad de tonos. */
    val zones: Int = 0,
) {
    /** Dirección hacia la luz en el sistema de la figura (X derecha, Y arriba, Z hacia quien mira de frente). */
    fun direction(): FloatArray {
        val az = Math.toRadians(azimuth.toDouble())
        val el = Math.toRadians(elevation.toDouble())
        return floatArrayOf((sin(az) * cos(el)).toFloat(), sin(el).toFloat(), (cos(az) * cos(el)).toFloat())
    }

    override fun equals(other: Any?): Boolean =
        other is LightParams && azimuth == other.azimuth && elevation == other.elevation && ambient == other.ambient &&
            cavity == other.cavity && color.contentEquals(other.color) && zones == other.zones

    override fun hashCode(): Int = arrayOf<Any>(azimuth, elevation, ambient, cavity, color.contentHashCode(), zones).contentHashCode()
}

/** Límites de cada zona (intensidad de luz 0–1) y su tono para el mapa de zonas. */
fun zoneThresholds(zones: Int): FloatArray = if (zones == 3) floatArrayOf(0.22f, 0.58f) else floatArrayOf(0.1f, 0.28f, 0.52f, 0.78f)

fun zoneColors(zones: Int): IntArray = if (zones == 3) {
    intArrayOf(0xFF30343F.toInt(), 0xFF7D8493.toInt(), 0xFFE9E5DA.toInt())
} else {
    intArrayOf(0xFF1E2129.toInt(), 0xFF454B59.toInt(), 0xFF7D8493.toInt(), 0xFFB9BEC7.toInt(), 0xFFF6F2E6.toInt())
}

fun zoneNames(zones: Int): List<String> = if (zones == 3) listOf("Sombra", "Medio", "Luz")
else listOf("Sombra profunda", "Sombra", "Medio", "Luz", "Luz máxima")

/**
 * Dibuja la malla con sombras proyectadas (mapa de sombras desde la luz), luz difusa, luz ambiental
 * del cielo y oscurecimiento por concavidad. Todo cambio de parámetros pide un cuadro nuevo.
 */
class RelightRenderer(private val requestRender: () -> Unit) : GLSurfaceView.Renderer {
    @Volatile var mesh: MeshData? = null
        set(v) { field = v; requestRender() }
    @Volatile var light = LightParams()
        set(v) { field = v; requestRender() }
    /** Cámara: giro horizontal y vertical (grados) y distancia. */
    @Volatile var yaw = 0f
    @Volatile var pitch = 12f
    @Volatile var distance = 4.4f
    /** Corrección de orientación del modelo en cuartos de vuelta. */
    @Volatile var turnsY = 0
    @Volatile var turnsX = 0
    @Volatile var albedo = floatArrayOf(0.62f, 0.62f, 0.63f)
    /** Se llama (desde el hilo de dibujo) si la malla no cabe en la memoria de video. */
    @Volatile var onUploadError: (() -> Unit)? = null

    private var uploaded: MeshData? = null
    private var vbo = 0
    private var ibo = 0
    private var indexCount = 0
    private var mainProg = 0
    private var shadowProg = 0
    private var shadowFbo = 0
    private var shadowTex = 0
    private val shadowSize = 2048
    private var width = 1
    private var height = 1

    fun changed() = requestRender()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        uploaded = null
        vbo = 0; ibo = 0
        mainProg = program(MAIN_VS, MAIN_FS)
        shadowProg = program(SHADOW_VS, SHADOW_FS)
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        shadowTex = tex[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT24, shadowSize, shadowSize, 0, GLES30.GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_INT, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_MODE, GLES30.GL_COMPARE_REF_TO_TEXTURE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_FUNC, GLES30.GL_LEQUAL)
        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        shadowFbo = fbo[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_TEXTURE_2D, shadowTex, 0)
        GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_NONE), 0)
        GLES30.glReadBuffer(GLES30.GL_NONE)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w.coerceAtLeast(1)
        height = h.coerceAtLeast(1)
    }

    private fun upload(m: MeshData) {
        if (vbo != 0) GLES30.glDeleteBuffers(2, intArrayOf(vbo, ibo), 0)
        val nv = m.vertexCount
        val fb = ByteBuffer.allocateDirect(nv * 7 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until nv) {
            fb.put(m.positions[i * 3]).put(m.positions[i * 3 + 1]).put(m.positions[i * 3 + 2])
            fb.put(m.normals[i * 3]).put(m.normals[i * 3 + 1]).put(m.normals[i * 3 + 2])
            fb.put(m.cavity[i])
        }
        fb.position(0)
        val ib = ByteBuffer.allocateDirect(m.indices.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
        ib.put(m.indices).position(0)
        val ids = IntArray(2)
        GLES30.glGenBuffers(2, ids, 0)
        vbo = ids[0]; ibo = ids[1]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, nv * 7 * 4, fb, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, m.indices.size * 4, ib, GLES30.GL_STATIC_DRAW)
        indexCount = m.indices.size
        uploaded = m
    }

    private fun bindAttribs() {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 28, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 28, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, 28, 24)
    }

    override fun onDrawFrame(gl: GL10?) {
        val m = mesh
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0.11f, 0.115f, 0.135f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (m == null) return
        if (m !== uploaded) {
            try {
                upload(m)
            } catch (e: OutOfMemoryError) {
                uploaded = m
                indexCount = 0
                onUploadError?.invoke()
            }
        }
        if (indexCount == 0) return
        val lp = light

        val model = FloatArray(16)
        Matrix.setIdentityM(model, 0)
        Matrix.rotateM(model, 0, turnsY * 90f, 0f, 1f, 0f)
        Matrix.rotateM(model, 0, turnsX * 90f, 1f, 0f, 0f)

        // Luz direccional: proyección ortográfica que abarca toda la figura (radio ≤ √3)
        val l = lp.direction()
        val lightView = FloatArray(16)
        val up = if (abs(l[1]) > 0.98f) floatArrayOf(0f, 0f, 1f) else floatArrayOf(0f, 1f, 0f)
        Matrix.setLookAtM(lightView, 0, l[0] * 4f, l[1] * 4f, l[2] * 4f, 0f, 0f, 0f, up[0], up[1], up[2])
        val lightProj = FloatArray(16)
        Matrix.orthoM(lightProj, 0, -1.8f, 1.8f, -1.8f, 1.8f, 0.5f, 8f)
        val lightVP = FloatArray(16)
        Matrix.multiplyMM(lightVP, 0, lightProj, 0, lightView, 0)
        val lightMVP = FloatArray(16)
        Matrix.multiplyMM(lightMVP, 0, lightVP, 0, model, 0)

        // 1) Mapa de sombras
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFbo)
        GLES30.glViewport(0, 0, shadowSize, shadowSize)
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(2f, 4f)
        GLES30.glUseProgram(shadowProg)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(shadowProg, "uMVP"), 1, false, lightMVP, 0)
        bindAttribs()
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)

        // 2) Figura
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        val yawR = Math.toRadians(yaw.toDouble())
        val pitchR = Math.toRadians(pitch.toDouble())
        val ex = (distance * cos(pitchR) * sin(yawR)).toFloat()
        val ey = (distance * sin(pitchR)).toFloat()
        val ez = (distance * cos(pitchR) * cos(yawR)).toFloat()
        val view = FloatArray(16)
        Matrix.setLookAtM(view, 0, ex, ey, ez, 0f, 0f, 0f, 0f, 1f, 0f)
        val proj = FloatArray(16)
        Matrix.perspectiveM(proj, 0, 35f, width.toFloat() / height, 0.1f, 50f)
        val vp = FloatArray(16)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)

        GLES30.glUseProgram(mainProg)
        fun u(n: String) = GLES30.glGetUniformLocation(mainProg, n)
        GLES30.glUniformMatrix4fv(u("uMVP"), 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(u("uModel"), 1, false, model, 0)
        GLES30.glUniformMatrix4fv(u("uLightMVP"), 1, false, lightMVP, 0)
        GLES30.glUniform3f(u("uLightDir"), l[0], l[1], l[2])
        GLES30.glUniform3f(u("uLightColor"), lp.color[0], lp.color[1], lp.color[2])
        GLES30.glUniform3f(u("uAlbedo"), albedo[0], albedo[1], albedo[2])
        GLES30.glUniform1f(u("uAmbient"), lp.ambient)
        GLES30.glUniform1f(u("uCavity"), lp.cavity)
        GLES30.glUniform1i(u("uZones"), lp.zones)
        val th = zoneThresholds(if (lp.zones == 3) 3 else 5)
        GLES30.glUniform4f(u("uThresh"), th[0], th[1], th.getOrElse(2) { 2f }, th.getOrElse(3) { 2f })
        val zc = zoneColors(if (lp.zones == 3) 3 else 5)
        val zf = FloatArray(15)
        for (i in 0 until 5) {
            val c = zc.getOrElse(i) { zc.last() }
            zf[i * 3] = ((c shr 16) and 0xFF) / 255f
            zf[i * 3 + 1] = ((c shr 8) and 0xFF) / 255f
            zf[i * 3 + 2] = (c and 0xFF) / 255f
        }
        GLES30.glUniform3fv(u("uZoneColors"), 5, zf, 0)
        GLES30.glUniform1f(u("uTexel"), 1f / shadowSize)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTex)
        GLES30.glUniform1i(u("uShadow"), 0)
        bindAttribs()
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0)
    }

    private fun program(vs: String, fs: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) android.util.Log.e("Gotario", "Shader: " + GLES30.glGetShaderInfoLog(s))
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        return p
    }

    private companion object {
        const val SHADOW_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
uniform mat4 uMVP;
void main() { gl_Position = uMVP * vec4(aPos, 1.0); }
"""
        const val SHADOW_FS = """#version 300 es
precision mediump float;
void main() { }
"""
        const val MAIN_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in float aCavity;
uniform mat4 uMVP;
uniform mat4 uModel;
uniform mat4 uLightMVP;
out vec3 vN;
out vec4 vShadow;
out float vCav;
void main() {
    gl_Position = uMVP * vec4(aPos, 1.0);
    vN = mat3(uModel) * aNormal;
    vShadow = uLightMVP * vec4(aPos, 1.0);
    vCav = aCavity;
}
"""
        const val MAIN_FS = """#version 300 es
precision highp float;
precision highp sampler2DShadow;
in vec3 vN;
in vec4 vShadow;
in float vCav;
uniform vec3 uLightDir;
uniform vec3 uLightColor;
uniform vec3 uAlbedo;
uniform float uAmbient;
uniform float uCavity;
uniform int uZones;
uniform vec4 uThresh;
uniform vec3 uZoneColors[5];
uniform float uTexel;
uniform sampler2DShadow uShadow;
out vec4 outColor;

float shadowAt(float ndl) {
    vec3 p = vShadow.xyz / vShadow.w * 0.5 + 0.5;
    if (p.x <= 0.0 || p.x >= 1.0 || p.y <= 0.0 || p.y >= 1.0 || p.z >= 1.0) return 1.0;
    float bias = mix(0.0025, 0.0006, ndl);
    float s = 0.0;
    for (int x = -1; x <= 1; x++)
        for (int y = -1; y <= 1; y++)
            s += texture(uShadow, vec3(p.xy + vec2(float(x), float(y)) * uTexel * 1.5, p.z - bias));
    return s / 9.0;
}

void main() {
    vec3 n = normalize(vN);
    if (!gl_FrontFacing) n = -n;
    float ndl = dot(n, normalize(uLightDir));
    float diff = max(ndl, 0.0);
    float sh = ndl > 0.0 ? shadowAt(ndl) : 0.0;
    float ao = clamp(1.0 - uCavity * clamp(vCav * 3.0, 0.0, 1.0) * 0.75, 0.25, 1.0);
    float sky = 0.65 + 0.35 * n.y;
    float direct = (1.0 - uAmbient) * diff * sh;
    float amb = uAmbient * ao * sky;
    float intensity = direct + amb * 1.4;
    if (uZones > 0) {
        int z;
        if (uZones == 3) z = intensity < uThresh.x ? 0 : (intensity < uThresh.y ? 1 : 2);
        else z = intensity < uThresh.x ? 0 : (intensity < uThresh.y ? 1 : (intensity < uThresh.z ? 2 : (intensity < uThresh.w ? 3 : 4)));
        outColor = vec4(uZoneColors[z], 1.0);
        return;
    }
    vec3 c = uAlbedo * (uLightColor * direct + vec3(amb)) * 1.25;
    // Directo a pantalla con corrección gamma y un tope suave para que la luz máxima no se queme
    c = c / (1.0 + 0.25 * c);
    outColor = vec4(pow(c, vec3(1.0 / 2.2)), 1.0);
}
"""
    }
}
