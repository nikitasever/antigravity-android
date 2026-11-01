#version 300 es
precision highp float;

#define E 2.71828182846
#define PI 3.14159265358979323844
#define NUM_OCTAVES 4

struct BloopColors {
    vec3 main;
    vec3 low;
    vec3 mid;
    vec3 high;
};

// Parameters
uniform float time;
uniform vec4 avgMag;
uniform vec4 cumulativeAudio;
uniform bool isNewBloop;

uniform vec3 bloopColorsMain;
uniform vec3 bloopColorsLow;
uniform vec3 bloopColorsMid;
uniform vec3 bloopColorsHigh;

uniform sampler2D noiseTexture;

// Input and output
in vec2 out_uv;
out vec4 FragColor;

vec4 permute(vec4 x) {
    return mod(((x * 34.0) + 1.0) * x, 289.0);
}

vec4 taylorInvSqrt(vec4 r) {
    return 1.79284291400159 - 0.85373472095314 * r;
}

vec3 fade(vec3 t) {
    return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
}

// Classic Perlin 3D Noise by Stefan Gustavson
float cnoise(vec3 P) {
    vec3 Pi0 = floor(P);  // Integer part for indexing
    vec3 Pi1 = Pi0 + 1.0; // Integer part + 1
    Pi0 = mod(Pi0, 289.0);
    Pi1 = mod(Pi1, 289.0);
    vec3 Pf0 = fract(P);  // Fractional part for interpolation
    vec3 Pf1 = Pf0 - 1.0; // Fractional part - 1.0
    vec4 ix = vec4(Pi0.x, Pi1.x, Pi0.x, Pi1.x);
    vec4 iy = vec4(Pi0.yy, Pi1.yy);
    vec4 iz0 = vec4(Pi0.z);
    vec4 iz1 = vec4(Pi1.z);

    vec4 ixy = permute(permute(ix) + iy);
    vec4 ixy0 = permute(ixy + iz0);
    vec4 ixy1 = permute(ixy + iz1);

    vec4 gx0 = ixy0 / 7.0;
    vec4 gy0 = fract(floor(gx0) / 7.0) - 0.5;
    gx0 = fract(gx0);
    vec4 gz0 = 0.5 - abs(gx0) - abs(gy0);
    vec4 sz0 = step(gz0, vec4(0.0));
    gx0 -= sz0 * (step(vec4(0.0), gx0) - 0.5);
    gy0 -= sz0 * (step(vec4(0.0), gy0) - 0.5);

    vec4 gx1 = ixy1 / 7.0;
    vec4 gy1 = fract(floor(gx1) / 7.0) - 0.5;
    gx1 = fract(gx1);
    vec4 gz1 = 0.5 - abs(gx1) - abs(gy1);
    vec4 sz1 = step(gz1, vec4(0.0));
    gx1 -= sz1 * (step(vec4(0.0), gx1) - 0.5);
    gy1 -= sz1 * (step(vec4(0.0), gy1) - 0.5);

    vec3 g000 = vec3(gx0.x, gy0.x, gz0.x);
    vec3 g100 = vec3(gx0.y, gy0.y, gz0.y);
    vec3 g010 = vec3(gx0.z, gy0.z, gz0.z);
    vec3 g110 = vec3(gx0.w, gy0.w, gz0.w);
    vec3 g001 = vec3(gx1.x, gy1.x, gz1.x);
    vec3 g101 = vec3(gx1.y, gy1.y, gz1.y);
    vec3 g011 = vec3(gx1.z, gy1.z, gz1.z);
    vec3 g111 = vec3(gx1.w, gy1.w, gz1.w);

    vec4 norm0 = taylorInvSqrt(vec4(dot(g000, g000), dot(g010, g010), dot(g100, g100), dot(g110, g110)));
    g000 *= norm0.x;
    g010 *= norm0.y;
    g100 *= norm0.z;
    g110 *= norm0.w;
    vec4 norm1 = taylorInvSqrt(vec4(dot(g001, g001), dot(g011, g011), dot(g101, g101), dot(g111, g111)));
    g001 *= norm1.x;
    g011 *= norm1.y;
    g101 *= norm1.z;
    g111 *= norm1.w;

    float n000 = dot(g000, Pf0);
    float n100 = dot(g100, vec3(Pf1.x, Pf0.yz));
    float n010 = dot(g010, vec3(Pf0.x, Pf1.y, Pf0.z));
    float n110 = dot(g110, vec3(Pf1.xy, Pf0.z));
    float n001 = dot(g001, vec3(Pf0.xy, Pf1.z));
    float n101 = dot(g101, vec3(Pf1.x, Pf0.y, Pf1.z));
    float n011 = dot(g011, vec3(Pf0.x, Pf1.yz));
    float n111 = dot(g111, Pf1);

    vec3 fade_xyz = fade(Pf0);
    vec4 n_z = mix(vec4(n000, n100, n010, n110), vec4(n001, n101, n011, n111), fade_xyz.z);
    vec2 n_yz = mix(n_z.xy, n_z.zw, fade_xyz.y);
    float n_xyz = mix(n_yz.x, n_yz.y, fade_xyz.x);
    return 2.2 * n_xyz;
}

float random(vec2 st) {
    return fract(sin(dot(st.xy, vec2(12.9898, 78.233))) * 43758.5453123);
}

float random(ivec2 st) {
    return random(vec2(st));
}

float random(float p) {
    return random(vec2(p));
}

float noise(vec2 p) {
    vec2 ip = floor(p);
    vec2 u = fract(p);
    u = u * u * (3.0 - 2.0 * u);

    float res = mix(mix(random(ip), random(ip + vec2(1.0, 0.0)), u.x),
    mix(random(ip + vec2(0.0, 1.0)), random(ip + vec2(1.0, 1.0)), u.x), u.y);
    return res * res;
}

// Fractional Brownian Motion function
float fbm(vec2 x) {
    float v = 0.0;
    float a = 0.5;
    vec2 shift = vec2(100.0);
    float c = cos(0.5);
    float s = sin(0.5);
    mat2 rot = mat2(vec2(c, s), vec2(-s, c));
    for (int i = 0; i < NUM_OCTAVES; ++i) {
        v += a * noise(x);
        x = (rot * x) * 2.0 + shift;
        a *= 0.5;
    }
    return v;
}

vec3 blendLinearBurn_13_5(vec3 base, vec3 blend) {
    // Note : Same implementation as BlendSubtract
    return max(base + blend - vec3(1.0), vec3(0.0));
}

vec3 blendLinearBurn_13_5(vec3 base, vec3 blend, float opacity) {
    return blendLinearBurn_13_5(base, blend) * opacity + base * (1.0 - opacity);
}

void main() {
    if (!isNewBloop) {
        discard;
    }

    BloopColors bloopColors;
    bloopColors.main = bloopColorsMain;
    bloopColors.low = bloopColorsLow;
    bloopColors.mid = bloopColorsMid;
    bloopColors.high = bloopColorsHigh;

    // General variables
    float scaleFactor = 1.0;
    vec2 uvCoord = ((out_uv - 0.5) * scaleFactor) + 0.5;

    // Variable settings
    float noiseScale = 1.25;
    float windSpeed = 0.075;
    float warpPower = 0.19;
    float waterColorNoiseScale = 18.0;
    float waterColorNoiseStrength = 0.010;
    float textureNoiseScale = 1.0;
    float textureNoiseStrength = 0.03;
    float verticalOffset = 0.090;
    float waveSpread = 1.0;
    float layer1Amplitude = 1.0;
    float layer1Frequency = 1.0;
    float layer2Amplitude = 1.0;
    float layer2Frequency = 1.0;
    float layer3Amplitude = 1.0;
    float layer3Frequency = 1.0;
    float fbmStrength = 1.0;
    float overallSoundScale = 1.0;
    float blurRadius = 0.9;
    float timescale = 1.4;

    // Time management
    float time = time * timescale * 0.85;

    vec3 sinOffsets = vec3(
        cumulativeAudio[0] * 0.15 * overallSoundScale,
        -cumulativeAudio[1] * 0.5 * overallSoundScale,
        cumulativeAudio[2] * 1.5 * overallSoundScale
    );
    verticalOffset += 1.0 - waveSpread;

    // UV noise displacement
    float noiseX = cnoise(vec3((uvCoord * 1.0) + vec2(0.0, 74.8572), (time + (cumulativeAudio[0] * 0.05 * overallSoundScale)) * 0.3));
    float noiseY = cnoise(vec3((uvCoord * 1.0) + vec2(203.91282, 10.0), (time + (cumulativeAudio[2] * 0.05 * overallSoundScale)) * 0.3));

    uvCoord += vec2(noiseX * 2.0, noiseY) * warpPower;

    // Watercolor noise
    float noiseA = cnoise(vec3((uvCoord * waterColorNoiseScale) + vec2(344.91282, 0.0), time * 0.3))
        + (cnoise(vec3((uvCoord * waterColorNoiseScale * 2.2) + vec2(723.937, 0.0), time * 0.4)) * 0.5);
    uvCoord += noiseA * waterColorNoiseStrength;
    uvCoord.y -= verticalOffset;

    // Texture noise
    vec2 textureUv = uvCoord * textureNoiseScale;
    float textureNoiseSampled = texture(noiseTexture, textureUv).r;
    float textureNoiseSampledBis = texture(noiseTexture, vec2(textureUv.x, 1.0 - textureUv.y)).g;

    float textureNoiseDisp0 = mix(
        (textureNoiseSampled - 0.5),
        (textureNoiseSampledBis - 0.5),
        (sin(time + cumulativeAudio[3] * 2.0) + 1.0) * 0.5
    ) * textureNoiseStrength;

    textureUv += vec2(63.861 + cumulativeAudio[0] * 0.05, 368.937);

    float textureNoiseDisp1 = mix(
        (textureNoiseSampled - 0.5),
        (textureNoiseSampledBis - 0.5),
        (sin(time + cumulativeAudio[3] * 2.0) + 1.0) * 0.5
    ) * textureNoiseStrength;

    textureUv += vec2(272.861, 829.937 + cumulativeAudio[1] * 0.1);
    textureUv += vec2(180.302 - cumulativeAudio[2] * 0.1, 819.871);

    float textureNoiseDisp3 = mix(
        (textureNoiseSampled - 0.5),
        (textureNoiseSampledBis - 0.5),
        (sin(time + cumulativeAudio[3] * 2.0) + 1.0) * 0.5
    ) * textureNoiseStrength;

    uvCoord += textureNoiseDisp0;

    // FBM noise
    vec2 st = uvCoord * noiseScale;

    vec2 q = vec2(0.0);
    q = vec2(fbm((st * 0.5) + windSpeed * (time + (cumulativeAudio[3] * 0.136 * overallSoundScale))));

    vec2 r = vec2(0.0);
    r.x = fbm(st + 1.0 * q + vec2(0.3, 9.2) + 0.15 * (time + (cumulativeAudio[1] * 0.234 * overallSoundScale)));
    r.y = fbm(st + 1.0 * q + vec2(8.3, 0.8) + 0.126 * (time + (cumulativeAudio[2] * 0.165 * overallSoundScale)));

    float f = fbm(st + r - q);
    float fullFbm = ((f + 0.6 * f * f + 0.7 * f) + 0.5) * 0.5;
    fullFbm = sqrt(fullFbm);

    fullFbm *= fbmStrength;

    // Sin waves
    blurRadius *= 1.5;

    vec2 snUv = (uvCoord + vec2((fullFbm - 0.5) * 1.2) + vec2(0.0, 0.025) + textureNoiseDisp0) * vec2(layer1Frequency, 1.0);
    float sn = noise(snUv * 2.0 + vec2(sin(sinOffsets.x * 0.25), time * 0.5 + sinOffsets.x)) * 2.0 * layer1Amplitude;
    float sn2 = smoothstep(
        sn - (1.2 * blurRadius),
        sn + (1.2 * blurRadius),
        ((snUv.y - (0.5 * waveSpread)) * (5.0 - (avgMag[0] * 0.1 * overallSoundScale) * 0.5)) + 0.5
    );

    vec2 snUvBis = (uvCoord + vec2((fullFbm - 0.5) * 0.85) + vec2(0.0, 0.025) + textureNoiseDisp1) * vec2(layer2Frequency, 1.0);
    float snBis = noise(snUvBis * 4.0 + vec2(sin(sinOffsets.y * 0.15) * 2.4 + 293.0, time * 1.0 + sinOffsets.y * 0.5)) * 2.0 * layer2Amplitude;
    float sn2Bis = smoothstep(
        snBis - ((0.9 + avgMag[1] * 0.4 * overallSoundScale) * blurRadius),
        snBis + ((0.9 + avgMag[1] * 0.8 * overallSoundScale) * blurRadius),
        ((snUvBis.y - (0.6 * waveSpread)) * (5.0 - avgMag[1] * 0.75)) + 0.5
    );

    vec2 snUvThird = (uvCoord + vec2((fullFbm - 0.5) * 1.1) + textureNoiseDisp3) * vec2(layer3Frequency, 1.0);
    float snThird = noise(snUvThird * 6.0 + vec2(sin(sinOffsets.z * 0.1) * 2.4 + 153.0, time * 1.2 + sinOffsets.z * 0.8)) * 2.0 * layer3Amplitude;
    float sn2Third = smoothstep(
        snThird - (0.7 * blurRadius),
        snThird + (0.7 * blurRadius),
        ((snUvThird.y - (0.9 * waveSpread)) * 6.0) + 0.5
    );

    // Gradient blending
    sn2 = pow(sn2, 0.8);
    sn2Bis = pow(sn2Bis, 0.9);

    // Sin colors compute and blending
    vec3 sinColor;
    sinColor = blendLinearBurn_13_5(bloopColors.main, bloopColors.low, 1.0 - sn2);
    sinColor = blendLinearBurn_13_5(sinColor, mix(bloopColors.main, bloopColors.mid, 1.0 - sn2Bis), sn2);
    sinColor = mix(sinColor, mix(bloopColors.main, bloopColors.high, 1.0 - sn2Third), sn2 * sn2Bis);

    // Output color
    FragColor = vec4(sinColor, 1.0);
}
