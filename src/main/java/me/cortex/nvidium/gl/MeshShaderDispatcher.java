package me.cortex.nvidium.gl;

import static org.lwjgl.opengl.EXTMeshShader.glDrawMeshTasksEXT;
import static org.lwjgl.opengl.EXTMeshShader.glMultiDrawMeshTasksIndirectEXT;
import static org.lwjgl.opengl.NVMeshShader.glDrawMeshTasksNV;
import static org.lwjgl.opengl.NVMeshShader.glMultiDrawMeshTasksIndirectNV;

import org.lwjgl.opengl.GL;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

@Lwjgl3Aware
public class MeshShaderDispatcher {

    public static final MeshShaderDispatcher INSTANCE = new MeshShaderDispatcher();
    private final boolean isEXT;

    public MeshShaderDispatcher() {
        GL.getCapabilities();
        this.isEXT = GL.getCapabilities().GL_EXT_mesh_shader;
    }

    public boolean usesExtMeshShaders() {
        return isEXT;
    }

    /**
     * Tells a terrain task shader where its slice of the command buffer starts. GL_EXT_mesh_shader task shaders index
     * the command buffer with gl_DrawID, which restarts at 0 for every multi-draw.
     */
    public void setDrawIdOffset(int program, int firstCommand) {
        if (isEXT) {
            org.lwjgl.opengl.GL41C.glProgramUniform1ui(program, 7, firstCommand);
        }
    }

    public void drawMeshTasks(int first, int count) {
        if (isEXT) {
            glDrawMeshTasksEXT(count, 1, 1);
        } else {
            glDrawMeshTasksNV(first, count);
        }
    }

    public void multiDrawMeshTasksIndirect(long indirectOffset, int drawCount, int stride) {
        if (isEXT) {
            glMultiDrawMeshTasksIndirectEXT(indirectOffset, drawCount, stride);
        } else {
            glMultiDrawMeshTasksIndirectNV(indirectOffset, drawCount, stride);
        }
    }
}
