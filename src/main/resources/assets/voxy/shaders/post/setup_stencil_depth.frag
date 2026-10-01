#version 330 core

layout(binding = 0) uniform sampler2D depthTex;
layout(push_constant) uniform PushConstants {
    vec2 scaleFactor;
};

#import <voxy:util/depthutils.glsl>

in vec2 UV;
void main() {
    gl_FragDepth = NEAR;
    if (texture(depthTex, UV*scaleFactor).r==FAR) {
        discard;
    }
}