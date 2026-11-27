#version 450

// a triangle without a vertex buffer: its corners and colors by the index of the vertex
layout(location = 0) out vec3 color;

const vec2 corners[3] = vec2[](vec2(0.0, -0.6), vec2(0.6, 0.6), vec2(-0.6, 0.6));
const vec3 colors[3] = vec3[](vec3(1.0, 0.2, 0.2), vec3(0.2, 1.0, 0.2), vec3(0.2, 0.4, 1.0));

void main() {
    gl_Position = vec4(corners[gl_VertexIndex], 0.0, 1.0);
    color = colors[gl_VertexIndex];
}
