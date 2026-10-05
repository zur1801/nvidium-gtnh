package me.cortex.nvidium.gl.shader;

import static org.lwjgl.opengl.GL20.glDeleteProgram;
import static org.lwjgl.opengl.GL20.glUseProgram;

import java.util.HashMap;
import java.util.Map;

import org.lwjgl.opengl.GL20C;

import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.gl.GlObject;
import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

@Lwjgl3Aware
public class Shader extends GlObject {

    private Shader(int program) {
        super(program);
    }

    public static Builder make(IShaderProcessor processor) {
        return new Builder(processor);
    }

    public static Builder make() {
        return new Builder((aa, source) -> source);
    }

    public void bind() {
        glUseProgram(id);
    }

    public void delete() {
        super.free0();
        glDeleteProgram(id);
    }

    @Override
    public void free() {
        this.delete();
    }

    @Lwjgl3Aware
    public static class Builder {

        private final Map<ShaderType, String> sources = new HashMap<>();
        private final IShaderProcessor processor;

        private Builder(IShaderProcessor processor) {
            this.processor = processor;
        }

        public Builder addSource(ShaderType type, String source) {
            sources.put(type, processor.process(type, source));
            return this;
        }

        // Program construction goes through RawGL so Angelica's GLSM does not rewrite or extend these programs
        public Shader compile() {
            int program = RawGL.createProgram();
            int[] shaders = sources.entrySet()
                .stream()
                .mapToInt(a -> createShader(a.getKey(), a.getValue()))
                .toArray();

            for (int i : shaders) {
                RawGL.attachShader(program, i);
            }
            RawGL.linkProgram(program);
            for (int i : shaders) {
                RawGL.detachShader(program, i);
                RawGL.deleteShader(i);
            }
            printProgramLinkLog(program);
            verifyProgramLinked(program);
            return new Shader(program);
        }

        private static void printProgramLinkLog(int program) {
            String log = GL20C.glGetProgramInfoLog(program);

            if (!log.isEmpty()) {
                System.err.println(log);
            }
        }

        private static void verifyProgramLinked(int program) {
            int result = GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS);

            if (result != GL20C.GL_TRUE) {
                throw new RuntimeException("Shader program linking failed, see log for details");
            }
        }

        private static int createShader(ShaderType type, String src) {
            int shader = RawGL.createShader(type.gl);
            RawGL.shaderSource(shader, src);
            RawGL.compileShader(shader);
            String log = GL20C.glGetShaderInfoLog(shader);

            if (!log.isEmpty()) {
                Nvidium.LOGGER.error(src);
                Nvidium.LOGGER.error(log);
            }

            int result = GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS);

            if (result != GL20C.GL_TRUE) {
                RawGL.deleteShader(shader);

                throw new RuntimeException("Shader compilation failed, see log for details");
            }

            return shader;
        }
    }

}
