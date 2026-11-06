package com.example.big.playback;

import android.graphics.Bitmap;
import android.graphics.RectF;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Two separable Gaussian passes, restricted to the feedback region. All coordinates are screen pixels. */
final class VideoFrameShader {
    private static final String VERTEX = "attribute vec2 vertex; varying vec2 uv;"
            + "void main(){ gl_Position=vec4(vertex,0.,1.); uv=vec2(vertex.x*.5+.5,.5-vertex.y*.5); }";
    private static final String COMMON = "precision highp float; varying vec2 uv;"
            + "uniform samplerExternalOES frame; uniform mat4 transform; uniform vec2 screen;"
            + "uniform sampler2D stillImage; uniform vec4 stillArea; uniform float useStill;"
            + "uniform vec4 label; uniform vec4 region; uniform float density; uniform float cropZoom;"
            + "vec4 video(vec2 p){ if(useStill>.5){ vec2 s=(p-stillArea.xy)/stillArea.zw;"
            + " if(s.x<0.||s.y<0.||s.x>1.||s.y>1.)return vec4(0.,0.,0.,1.);"
            + " return texture2D(stillImage,s); } vec2 t=clamp((p/screen-.5)/cropZoom+.5,vec2(0.),vec2(1.));"
            + " return texture2D(frame,(transform*vec4(t.x,1.-t.y,0.,1.)).xy); }"
            + "float fadeAt(vec2 p){ vec2 size=label.zw-label.xy;"
            + " float radius=min(24.*density,min(size.x,size.y)*.5);"
            + " vec2 q=abs(p-(label.xy+label.zw)*.5)-(size*.5-radius);"
            + " float inside=-(length(max(q,0.))+min(max(q.x,q.y),0.)-radius);"
            + " return smoothstep(0.,min(24.*density,min(size.x,size.y)*.5),inside); }"
            + "float weight(int i){ return exp(-float(i*i)/72.); }";
    private static final String PREFIX = "#extension GL_OES_EGL_image_external : require\n";
    private static final String COPY = PREFIX + COMMON + "void main(){ gl_FragColor=video(uv*screen); }";
    private static final String DOWNSAMPLE = PREFIX + COMMON
            + "vec4 filtered(vec2 p){ vec4 c=vec4(0.);"
            + " for(int y=-1;y<=2;y++){ for(int x=-1;x<=2;x++){"
            + " c+=video(p+vec2(float(x)-.5,float(y)-.5)); }} return c/16.; }"
            + "void main(){ gl_FragColor=filtered(region.xy+uv*region.zw); }";
    private static final String HORIZONTAL = PREFIX + COMMON + "uniform sampler2D sourceImage;"
            + "void main(){ vec2 p=region.xy+uv*region.zw; float stepSize=density*fadeAt(p);"
            + " vec4 c=vec4(0.); float weights=0.; for(int i=-16;i<=16;i++){"
            + " vec2 t=uv+vec2(float(i)*stepSize/region.z,0.);"
            + " float w=weight(i); c+=texture2D(sourceImage,vec2(t.x,1.-t.y))*w; weights+=w; }"
            + " gl_FragColor=vec4(c.rgb/weights,1.); }";
    private static final String VERTICAL = PREFIX + COMMON + "uniform sampler2D horizontal;"
            + "void main(){ vec2 p=uv*screen; float fade=fadeAt(p); vec4 sharp=video(p);"
            + " if(fade<=0.){ gl_FragColor=sharp; return; }"
            + " vec3 c=vec3(0.); float weights=0.; for(int i=-16;i<=16;i++){"
            + " vec2 t=(p+vec2(0.,float(i)*density*fade)-region.xy)/region.zw;"
            + " float w=weight(i); c+=texture2D(horizontal,vec2(t.x,1.-t.y)).rgb*w; weights+=w; }"
            + " gl_FragColor=vec4(mix(sharp.rgb,c/weights*(1.-.38*fade),fade),1.); }";

    private final FloatBuffer vertices = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer();
    private final Program copy;
    private final Program downsample;
    private final Program horizontal;
    private final Program vertical;
    private final RectF region = new RectF();
    private final RectF empty = new RectF();
    private int buffer;
    private int texture;
    private int sourceBuffer;
    private int sourceTexture;
    private int bufferWidth;
    private int bufferHeight;
    private int stillTexture;
    private int stillWidth;
    private int stillHeight;
    private int stillFit;
    private double displayAspect;
    private boolean cropToFill;
    private boolean showingStill;

    VideoFrameShader() {
        vertices.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
        copy = program(COPY);
        downsample = program(DOWNSAMPLE);
        horizontal = program(HORIZONTAL);
        vertical = program(VERTICAL);
    }

    void draw(int input, float[] transform, int width, int height, RectF label, float density) {
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, width, height);
        bind(copy, input, transform, width, height, label, empty, density);
        quad();
        if (label.isEmpty()) {
            clearBlur();
            return;
        }
        float padding = 16 * density + 3;
        region.set(label);
        region.inset(-padding, -padding);
        // Half-resolution intermediates and closely spaced taps avoid visible sampling bands.
        ensureBuffer(Math.max(1, (int) Math.ceil(region.width() / 2)),
                Math.max(1, (int) Math.ceil(region.height() / 2)));
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sourceBuffer);
        GLES20.glViewport(0, 0, bufferWidth, bufferHeight);
        bind(downsample, input, transform, width, height, label, region, density);
        quad();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, buffer);
        bind(horizontal, input, transform, width, height, label, region, density);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTexture);
        GLES20.glUniform1i(horizontal.sourceSampler, 1);
        quad();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, width, height);
        bind(vertical, input, transform, width, height, label, region, density);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(vertical.sampler, 1);
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
        int left = Math.max(0, (int) Math.floor(label.left));
        int top = Math.max(0, (int) Math.floor(label.top));
        int right = Math.min(width, (int) Math.ceil(label.right));
        int bottom = Math.min(height, (int) Math.ceil(label.bottom));
        GLES20.glScissor(left, height - bottom, Math.max(0, right - left), Math.max(0, bottom - top));
        quad();
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }

    private void bind(Program program, int input, float[] transform, int width, int height,
                      RectF label, RectF region, float density) {
        GLES20.glUseProgram(program.name);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, input);
        GLES20.glUniform1i(program.frame, 0);
        GLES20.glUniformMatrix4fv(program.transform, 1, false, transform, 0);
        GLES20.glUniform2f(program.screen, width, height);
        GLES20.glUniform1f(program.cropZoom, cropToFill ? VideoAspect.cropZoom(width, height, displayAspect) : 1);
        GLES20.glUniform1f(program.useStill, showingStill ? 1 : 0);
        GLES20.glUniform1i(program.stillImage, 2);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, stillTexture);
        float imageWidth = width;
        float imageHeight = height;
        if (showingStill) {
            double aspect = VideoAspect.previewAspect(displayAspect, stillWidth, stillHeight);
            imageWidth = VideoAspect.width(width, height, aspect, stillFit);
            imageHeight = VideoAspect.height(width, height, aspect, stillFit);
        }
        GLES20.glUniform4f(program.stillArea, (width - imageWidth) / 2, (height - imageHeight) / 2,
                imageWidth, imageHeight);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        if (program.label >= 0) {
            GLES20.glUniform4f(program.label, label.left, label.top, label.right, label.bottom);
            GLES20.glUniform1f(program.density, density);
        }
        if (program.region >= 0) {
            GLES20.glUniform4f(program.region, region.left, region.top, region.width(), region.height());
        }
        GLES20.glEnableVertexAttribArray(program.vertex);
        GLES20.glVertexAttribPointer(program.vertex, 2, GLES20.GL_FLOAT, false, 0, vertices);
    }

    private void quad() {
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    void setVideoAspect(double aspect, boolean cropToFill) {
        // Live OES frames already contain VLC's SAR, rotation, crop and letterboxing.
        // Preview bitmaps contain rotated pixels but are not a reliable display-size contract.
        displayAspect = aspect;
        this.cropToFill = cropToFill;
    }

    void setStill(Bitmap bitmap, int fit) {
        if (stillTexture == 0) {
            int[] names = new int[1];
            GLES20.glGenTextures(1, names, 0);
            stillTexture = names[0];
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, stillTexture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        stillWidth = bitmap.getWidth();
        stillHeight = bitmap.getHeight();
        stillFit = fit;
        showingStill = true;
    }

    void clearStill() {
        showingStill = false;
        if (stillTexture != 0) GLES20.glDeleteTextures(1, new int[]{stillTexture}, 0);
        stillTexture = 0;
    }

    private void ensureBuffer(int width, int height) {
        if (buffer != 0 && width == bufferWidth && height == bufferHeight) {
            return;
        }
        clearBlur();
        bufferWidth = width;
        bufferHeight = height;
        int[] names = new int[2];
        GLES20.glGenTextures(2, names, 0);
        texture = names[0];
        sourceTexture = names[1];
        for (int name : names) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, name);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        }
        GLES20.glGenFramebuffers(2, names, 0);
        buffer = names[0];
        sourceBuffer = names[1];
        for (int name : names) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, name);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, name == buffer ? texture : sourceTexture, 0);
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Video blur framebuffer unavailable");
            }
        }
    }

    private void clearBlur() {
        if (buffer == 0 && texture == 0) {
            return;
        }
        GLES20.glDeleteFramebuffers(2, new int[]{buffer, sourceBuffer}, 0);
        GLES20.glDeleteTextures(2, new int[]{texture, sourceTexture}, 0);
        buffer = 0;
        texture = 0;
        sourceBuffer = 0;
        sourceTexture = 0;
    }

    void release() {
        clearBlur();
        clearStill();
        GLES20.glDeleteProgram(copy.name);
        GLES20.glDeleteProgram(downsample.name);
        GLES20.glDeleteProgram(horizontal.name);
        GLES20.glDeleteProgram(vertical.name);
    }

    private static Program program(String fragment) {
        int vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX);
        int pixel = compile(GLES20.GL_FRAGMENT_SHADER, fragment);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, pixel);
        GLES20.glLinkProgram(program);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(pixel);
        int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            String message = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new IllegalStateException(message);
        }
        return new Program(program);
    }

    private static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String message = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException(message);
        }
        return shader;
    }

    private static final class Program {
        final int name;
        final int vertex;
        final int frame;
        final int transform;
        final int screen;
        final int label;
        final int region;
        final int density;
        final int sampler;
        final int sourceSampler;
        final int stillImage;
        final int stillArea;
        final int useStill;
        final int cropZoom;

        Program(int name) {
            this.name = name;
            vertex = GLES20.glGetAttribLocation(name, "vertex");
            frame = GLES20.glGetUniformLocation(name, "frame");
            transform = GLES20.glGetUniformLocation(name, "transform");
            screen = GLES20.glGetUniformLocation(name, "screen");
            label = GLES20.glGetUniformLocation(name, "label");
            region = GLES20.glGetUniformLocation(name, "region");
            density = GLES20.glGetUniformLocation(name, "density");
            sampler = GLES20.glGetUniformLocation(name, "horizontal");
            sourceSampler = GLES20.glGetUniformLocation(name, "sourceImage");
            stillImage = GLES20.glGetUniformLocation(name, "stillImage");
            stillArea = GLES20.glGetUniformLocation(name, "stillArea");
            useStill = GLES20.glGetUniformLocation(name, "useStill");
            cropZoom = GLES20.glGetUniformLocation(name, "cropZoom");
        }
    }
}