// Sun/moon direction lighting for terrain.
//
// Celeritas bakes Minecraft's fixed per-face shading (top 1.0, north/south 0.8, east/west 0.6, bottom 0.5) into the
// vertex colour. Where a surface can see the sky, that fixed shading is divided back out and replaced by lighting
// from the current sun or moon direction. Caves and other unlit-by-sky areas keep the vanilla look.
// Requires the scene uniforms (lightDirection, lightColour) and the vertex format to be imported first.

// Vanilla's fixed shade for an axis aligned face. Angled geometry (plants, models) is not face shaded by vanilla.
float vanillaFaceShade(vec3 normal) {
    vec3 a = abs(normal);
    if (a.y > 0.9) return normal.y > 0.0 ? 1.0 : 0.5;
    if (a.z > 0.9) return 0.8;
    if (a.x > 0.9) return 0.6;
    return 1.0;
}

// Face normal of a triangle from its (counter-clockwise, front facing) vertices.
vec3 triangleNormal(Vertex A, Vertex B, Vertex C) {
    vec3 a = decodeVertexPosition(A);
    vec3 n = cross(decodeVertexPosition(B) - a, decodeVertexPosition(C) - a);
    float len = length(n);
    return len > 1e-8 ? n / len : vec3(0.0, 1.0, 0.0);
}

// Multiplier for the already vanilla-shaded terrain colour. skyLight is the 0-1 sky light level of the face.
vec3 computeSunShading(vec3 normal, float skyLight) {
    float strength = lightDirection.w;
    if (strength <= 0.0) {
        return vec3(1.0);
    }

    float ndl = max(dot(normal, lightDirection.xyz), 0.0);
    // Sky ambient (brighter on top, darker underneath) plus direct light from the sun or moon, which carries the
    // warm sunrise/sunset or cool moonlight colour. Shadowed sides and undersides stay at vanilla brightness
    // (0.6 / 0.5) so terrain still matches Distant Horizons' vanilla-shaded LODs; the sun only adds light:
    // side facing the sun 1.0, top at noon 1.1.
    float ambient = 0.6 + 0.1 * normal.y;
    vec3 lit = vec3(ambient) + (0.4 * ndl) * lightColour.rgb;

    float exposure = smoothstep(0.4, 0.9, skyLight) * strength;
    return mix(vec3(1.0), lit / vanillaFaceShade(normal), exposure);
}
