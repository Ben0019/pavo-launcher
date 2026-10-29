package com.ben.pavo;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * GPU-only fill-rate workload used behind the beaker overlay. The shader is
 * intentionally heavy and the surface stays behind the opaque stage, so it
 * stresses the GPU without becoming another graph.
 */
final class BenchmarkGpuWorkloadView extends GLSurfaceView implements GLSurfaceView.Renderer {
    private static final String VERTEX =
            "attribute vec4 aPos;\n"
            + "void main() { gl_Position = aPos; }\n";

    private static final String FRAGMENT =
            "precision highp float;\n"
            + "uniform float uTime;\n"
            + "uniform vec2 uResolution;\n"
            + "void main() {\n"
            + "  vec2 p = gl_FragCoord.xy / max(uResolution, vec2(1.0)) * 2.0 - 1.0;\n"
            + "  float v = 0.0;\n"
            + "  for (int i = 0; i < 160; i++) {\n"
            + "    float fi = float(i);\n"
            + "    p += vec2(sin(p.y * 3.0 + uTime * 0.35 + fi * 0.13),\n"
            + "              cos(p.x * 2.7 - uTime * 0.27 + fi * 0.17)) * 0.014;\n"
            + "    v += sin(p.x * 4.0 + uTime + fi * 0.2)\n"
            + "       * cos(p.y * 3.5 - uTime * 0.8 + fi * 0.1);\n"
            + "  }\n"
            + "  vec3 col = vec3(0.02, 0.035, 0.06) + vec3(0.20, 0.12, 0.10) * v * 0.04;\n"
            + "  gl_FragColor = vec4(col, 1.0);\n"
            + "}\n";

    private final FloatBuffer quad;
    private int program;
    private int aPos;
    private int uTime;
    private int uResolution;
    private volatile boolean running;
    private volatile float fps;
    private long frames;
    private long fpsWindowStart;

    BenchmarkGpuWorkloadView(Context context) {
        super(context);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        setClickable(false);
        setFocusable(false);
        float[] vertices = {
                -1f, -1f,
                 1f, -1f,
                -1f,  1f,
                 1f,  1f
        };
        quad = ByteBuffer.allocateDirect(vertices.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(vertices).position(0);
    }

    void setRunning(boolean run) {
        running = run;
        if (run) {
            frames = 0;
            fpsWindowStart = SystemClock.uptimeMillis();
            setRenderMode(RENDERMODE_CONTINUOUSLY);
            requestRender();
        } else {
            fps = 0f;
            setRenderMode(RENDERMODE_WHEN_DIRTY);
            requestRender();
        }
    }

    float fps() {
        return fps;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        int vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT);
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        uTime = GLES20.glGetUniformLocation(program, "uTime");
        uResolution = GLES20.glGetUniformLocation(program, "uResolution");
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (!running) return;
        GLES20.glUseProgram(program);
        GLES20.glEnableVertexAttribArray(aPos);
        quad.position(0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glUniform1f(uTime, (SystemClock.uptimeMillis() % 100000L) / 1000f);
        GLES20.glUniform2f(uResolution, Math.max(1, getWidth()), Math.max(1, getHeight()));
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPos);

        frames++;
        long now = SystemClock.uptimeMillis();
        if (now - fpsWindowStart >= 500L) {
            fps = frames * 1000f / Math.max(1L, now - fpsWindowStart);
            frames = 0;
            fpsWindowStart = now;
        }
    }

    private int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        return shader;
    }
}
