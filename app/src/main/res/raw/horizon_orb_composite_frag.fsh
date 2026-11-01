#version 300 es

precision highp float;
precision highp sampler2D;

in vec2 sdfPosition;
out vec4 fragColor;

uniform float uOrbScale;
uniform sampler2D uMaterialTexture;
uniform sampler2D uHalloweenGhost;
uniform vec2 uHalloweenGhostPresentation;

const float MIN_HORIZON_ORB_SCALE = 0.75;

vec3 applyHalloweenGhost(vec3 materialColor, vec2 materialUv) {
  float sway = uHalloweenGhostPresentation.y;
  float angle = sway * (3.0 * 3.14159265 / 180.0);
  float sine = sin(angle);
  float cosine = cos(angle);
  vec2 anchor = vec2(0.54, 0.78);
  // The artwork uses top-left coordinates; the GL material texture uses bottom-left coordinates.
  vec2 artworkUv = vec2(materialUv.x, 1.0 - materialUv.y);
  vec2 position = artworkUv - vec2(0.012 * sway, 0.0) - anchor;
  vec2 ghostCoordinate = vec2(
    cosine * position.x + sine * position.y,
    -sine * position.x + cosine * position.y
  ) + anchor;
  vec4 ghost = texture(uHalloweenGhost, ghostCoordinate);
  return ghost.rgb + materialColor * (1.0 - ghost.a);
}

void main() {
  float orbScale = max(clamp(uOrbScale, 0.0, 1.0), MIN_HORIZON_ORB_SCALE);
  vec2 scaledSdfPosition = sdfPosition / orbScale;
  float sdfDistance = length(scaledSdfPosition) - 1.0;
  float edgeWidth = max(fwidth(sdfDistance), 0.000001);
  if (sdfDistance >= edgeWidth * 2.0) {
    fragColor = vec4(0.0);
    return;
  }

  vec2 materialUv = scaledSdfPosition * 0.5 + 0.5;
  vec4 materialColor = texture(uMaterialTexture, materialUv);
  if (uHalloweenGhostPresentation.x == 1.0) {
    materialColor.rgb = applyHalloweenGhost(materialColor.rgb, materialUv);
  }
  float clampedShape = 1.0 - smoothstep(-edgeWidth, edgeWidth, sdfDistance + (edgeWidth * 0.5));
  float alpha = materialColor.a * clampedShape;
  fragColor = vec4(materialColor.rgb * alpha, alpha);
}
