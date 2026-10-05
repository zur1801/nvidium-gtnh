package me.cortex.nvidium.gl.shader;

import java.nio.ByteBuffer;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/**
 * Shader/program construction that calls the driver directly instead of through LWJGL's GL classes.
 * <p>
 * Angelica 2.2's GLSM redirector rewrites every {@code org.lwjgl.opengl.GL*} shader call into GLStateManager, which
 * runs Angelica's compatibility transformer over the GLSL and, at link time, attaches a generated vertex shader to any
 * program without one. Nvidium's programs are task + mesh + fragment, and NVIDIA refuses to link a mesh shader together
 * with a vertex shader. Calls through {@link JNI} are not redirected, so Nvidium's programs stay untouched.
 */
@Lwjgl3Aware
final class RawGL {

    private RawGL() {}

    private static GLCapabilities caps() {
        return GL.getCapabilities();
    }

    static int createProgram() {
        return JNI.callI(caps().glCreateProgram);
    }

    static int createShader(int type) {
        return JNI.callI(type, caps().glCreateShader);
    }

    static void shaderSource(int shader, CharSequence source) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer encoded = MemoryUtil.memUTF8(source, false);
            try {
                long strings = stack.mallocPointer(1)
                    .put(0, MemoryUtil.memAddress(encoded))
                    .address();
                long lengths = MemoryUtil.memAddress(
                    stack.mallocInt(1)
                        .put(0, encoded.remaining()));
                JNI.callPPV(shader, 1, strings, lengths, caps().glShaderSource);
            } finally {
                MemoryUtil.memFree(encoded);
            }
        }
    }

    static void compileShader(int shader) {
        JNI.callV(shader, caps().glCompileShader);
    }

    static void attachShader(int program, int shader) {
        JNI.callV(program, shader, caps().glAttachShader);
    }

    static void detachShader(int program, int shader) {
        JNI.callV(program, shader, caps().glDetachShader);
    }

    static void deleteShader(int shader) {
        JNI.callV(shader, caps().glDeleteShader);
    }

    static void linkProgram(int program) {
        JNI.callV(program, caps().glLinkProgram);
    }
}
