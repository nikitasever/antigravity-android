#version 300 es
precision highp float;
in vec2 textureCoord;
out vec4 fragColor;
uniform vec2 resolution;
uniform float pixelRatio;
uniform float scale;
uniform float eyeEdge;
uniform vec3 eyeDirection;
uniform vec3 bodyColor;
uniform float form;
uniform vec3 rotationX;
uniform vec3 rotationY;

// Unmodified projectedEye from the supplied developer handoff.

float projectedEye(vec2 point, vec3 direction, vec3 up, float edge, float aspect, float guard, float cssPixel, float pixel, float filterWidth){
  float guideRadius=max(0.1,1.0-max(0.0225,1.25*cssPixel)*guard);
  float eyeRadius=min(sqrt(max(0.0,1.0-edge*edge)),guideRadius-0.001);
  float planeDistance=sqrt(max(0.0,guideRadius*guideRadius-eyeRadius*eyeRadius));
  // Remain visible beyond 90 degrees, until the cap's last edge actually
  // crosses the smaller sphere's horizon (about 115 degrees at default size).
  if(direction.z < -eyeRadius/guideRadius)return 0.0;
  float lateral=length(direction.xy);
  vec2 bearing=lateral>0.00001?direction.xy/lateral:vec2(1.0,0.0);
  vec2 p=vec2(dot(point,bearing),dot(point,vec2(-bearing.y,bearing.x)));
  float horizon=length(point)-guideRadius;
  float planeSide=(planeDistance-lateral*p.x)/max(lateral,0.00001);
  float distanceFromEdge=planeSide;
  if(abs(direction.z)>0.0001){
    vec2 axes=vec2(eyeRadius*abs(direction.z),eyeRadius);
    vec2 local=(p-vec2(lateral*planeDistance,0.0))/axes;
    float ellipse=(dot(local,local)-1.0)/max(2.0*length(local/axes),0.00001);
    // At the limb the projected ellipse meets the guide sphere's circular
    // horizon, giving a smoothly curved occlusion on the same colored body.
    distanceFromEdge=direction.z>0.0?min(ellipse,planeSide):max(-ellipse,planeSide);
  }
  distanceFromEdge=max(distanceFromEdge,horizon);
  float aa=max(pixel*filterWidth,0.00001);
  return 1.0-smoothstep(-aa,aa,distanceFromEdge);
}

vec3 linearToSrgb(vec3 value) {
    return mix(1.055 * pow(max(value, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055,
               value * 12.92, lessThanEqual(value, vec3(0.0031308)));
}
// The source TorusGeometry(112, 47) is centered in XY, under an orthographic camera.
// Transform rays by the inverse ZYX rotation and trace its exact circular-tube SDF.
float torusDistance(vec3 world) {
    vec3 local = vec3(dot(world, rotationX), dot(world, rotationY), dot(world, eyeDirection));
    return length(vec2(length(local.xy) - 112.0 / 159.0, local.z)) - 47.0 / 159.0;
}
float torusCoverage(vec2 point, float pixel) {
    float bound = 1.0 + pixel;
    if (length(point) > bound) return 0.0;
    float depth = sqrt(max(0.0, bound * bound - dot(point, point)));
    float z = depth;
    float nearest = 2.0;
    for (int i = 0; i < 128; i++) {
        float distance = torusDistance(vec3(point, z));
        nearest = min(nearest, distance);
        if (distance < pixel * 0.05) return 1.0;
        if (z < -depth) break;
        z -= max(distance, pixel * 0.1);
    }
    return 1.0 - smoothstep(0.0, pixel, nearest);
}
void main() {
    // Leave room for the source opening's 4% overshoot.
    float radius = min(resolution.x, resolution.y) * 0.48 * max(scale, 0.00001);
    vec2 point = (gl_FragCoord.xy - resolution * 0.5) / radius;
    float pixel = 1.0 / radius;
    float body = 1.0 - smoothstep(-pixel * 0.5, pixel * 0.5, length(point) - 1.0);
    float eye = projectedEye(point, eyeDirection, vec3(0.0, 1.0, 0.0), eyeEdge,
                             1.0, 1.0, pixel * pixelRatio, pixel, 1.15);
    float sphereAlpha = body * (1.0 - eye);
    vec4 sphere = vec4(linearToSrgb(bodyColor) * sphereAlpha, sphereAlpha);
    if (form > 0.0) {
        float coverage = torusCoverage(point, pixel);
        fragColor = mix(sphere, vec4(linearToSrgb(bodyColor) * coverage, coverage), form);
    } else {
        fragColor = sphere;
    }
}
