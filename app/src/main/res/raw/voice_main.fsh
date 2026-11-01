#version 300 es
precision highp float;

#define E 2.71828182846
#define PI 3.14159265358979323844

struct BloopColors {
    vec3 main;
    vec3 low;
    vec3 mid;
    vec3 high;
};

struct ColoredSDF {
    float distance;
    vec4 color;
};

struct SDFArgs {
    vec2 st;
    float amount;
    float duration;
    float time;
    float mainRadius;
    vec4 displayColor;
};

// Parameters
uniform float time;
uniform float micLevel;
uniform float stateListen;
uniform float listenTimestamp;
uniform float stateThink;
uniform float thinkTimestamp;
uniform float stateSpeak;
uniform float speakTimestamp;
uniform float readyTimestamp;
uniform float stateHalt;
uniform float haltTimestamp;
uniform float stateFailedToConnect;
uniform float failedToConnectTimestamp;

uniform vec4 avgMag;
uniform float avgMagOverall;
uniform vec4 cumulativeAudio;
uniform float silenceAmount;
uniform float silenceTimestamp;
uniform bool isNewBloop;

uniform sampler2D flowmapThinking;

uniform vec3 baseColor;
uniform vec3 bloopColorsMain;
uniform vec3 bloopColorsLow;
uniform vec3 bloopColorsMid;
uniform vec3 bloopColorsHigh;

uniform sampler2D prepassTexture;

// Input and output
in vec2 out_uv;
out vec4 FragColor;

// Function Definitions

float spring(float t, float d) {
    return 1.0 - (exp(-E * 2.0 * t) * cos((1.0 - d) * 115.0 * t));
}

float silkySmooth(float t, float k) {
    return (atan(k * sin((t - 0.5) * PI)) / atan(k)) * 0.5 + 0.5;
}

float scaled(float edge0, float edge1, float x) {
    return clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
}

float fixedSpring(float t, float d) {
    float s = mix((1.0 - (exp(-E * 2.0 * t) * cos((1.0 - d) * 115.0 * t))), 1.0, scaled(0.0, 1.0, t));
    return s * (1.0 - t) + t;
}

float bounce(float t, float d) {
    return -sin(PI * (1.0 - d) * t) * (1.0 - t) * exp(-E * 2.0 * t) * t * 10.0;
}

float blendOverlay_9_12(float base, float blend) {
    return base < 0.5 ? (2.0 * base * blend) : 1.0 - 2.0 * (1.0 - base) * (1.0 - blend);
}

vec3 blendOverlay_9_12(vec3 base, vec3 blend) {
    return vec3(
        blendOverlay_9_12(base.r, blend.r),
        blendOverlay_9_12(base.g, blend.g),
        blendOverlay_9_12(base.b, blend.b)
    );
}

vec3 blendOverlay_9_12(vec3 base, vec3 blend, float opacity) {
    return mix(base, blendOverlay_9_12(base, blend), opacity);
}

float opSmoothUnion(float d1, float d2, float k) {
    if (k <= 0.0) {
        k = 0.000001;
    }
    float h = clamp(0.5 + 0.5 * (d2 - d1) / k, 0.0, 1.0);
    return mix(d2, d1, h) - k * h * (1.0 - h);
}

float sdRoundedBox(vec2 p, vec2 b, vec4 r) {
    r.xy = (p.x > 0.0) ? r.xy : r.zw;
    r.x = (p.y > 0.0) ? r.x : r.y;
    vec2 q = abs(p) - b + r.x;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r.x;
}

// Apply Idle State Function
ColoredSDF applyIdleState(ColoredSDF sdf, SDFArgs args) {
    float midRadius = 0.12;     // Radius after the initial spring animation
    float maxRadius = 0.3;      // Maximum radius to approach
    float t1 = 1.0;             // Time at which the spring animation ends
    float gamma = 3.0;          // Damping coefficient for the spring
    float omega = PI / 2.0;     // Angular frequency for the spring

    // Compute 'k' to ensure continuity in derivative at t = t1
    float k = exp(-gamma) * omega;

    float radius = 1.0;
    if (args.time <= t1) {
        // Normalized time for the spring function
        float t_prime = args.time / t1;
        // Spring function: damped harmonic oscillator
        float springValue = 1.0 - exp(-gamma * t_prime) * cos(omega * t_prime);
        radius = midRadius * springValue;
    } else {
        // Time adjusted for the exponential growth phase
        float adjustedTime = args.time - t1;
        // Exponential growth from midRadius to maxRadius
        radius =midRadius + (maxRadius - midRadius) * (1.0 - exp(-k * adjustedTime));
    }

    // Distance from the current point to the center of the bloop
    float distance = length(args.st) - radius;

    // Blend the distance with the existing sdf distance
    sdf.distance = mix(sdf.distance, distance, args.amount);

    // Bloop should be white in dark mode and black in light mode. The alpha
    // oscillates between 0.65 and 1 every 0.7 seconds
    float alpha = sin((PI / 0.7) * args.time) * 0.175 + 0.825;
    vec4 color = vec4(vec3(args.displayColor), alpha);

    // Blend the selected color with the existing sdf color
    sdf.color = mix(sdf.color, color, args.amount);

    return sdf;
}

// Applies a "speak" state effect based on audio magnitude data.
//
// Parameters:
// - sdf: The original signed distance field with color information.
// - args: Struct containing shader arguments such as coordinates and time.
// - avgMag: Array of normalized audio magnitudes across different frequency
//           bands (from low to high).
//
// Returns:
// - Modified ColoredSDF with updated distance and color based on audio data.
//
// Note:
// - Coordinate system ranges from [-0.5, 0.5] for both axes.
//   Positive values move down and to the right due to the top-left origin.
ColoredSDF applyListenAndSpeakState(ColoredSDF sdf,
                                    SDFArgs args,
                                    float micLevel,
                                    vec4 avgMag,
                                    vec4 cumulativeAudio,
                                    BloopColors bloopColors,
                                    sampler2D prepassTexture,
                                    sampler2D flowmapThinking,
                                    float thinkingState,
                                    bool listening,
                                    bool isNewBloop) {
    float entryAnimation = fixedSpring(scaled(0.0, 2.0, args.duration), 0.92);

    // Radius for the bloop
    float radius = (listening ? 0.37 : 0.43) * (1.0 - (1.0 - entryAnimation) * 0.25) + micLevel * 0.065;

    // Maximum vertical displacement for the hovering animation
    float maxDisplacement = 0.01;

    // Define the full cycle duration for the vertical oscillation in seconds
    float oscillationPeriod = 4.0;
    // Calculate vertical offset using a sine wave for smooth up and down motion
    float displacementOffset = maxDisplacement * sin((2.0 * PI / oscillationPeriod) * args.time);
    // Adjust coordinates by the vertical offset
    vec2 adjusted_st = args.st - vec2(0.0, displacementOffset);

    // We just want a flat bloop if this is Standard — way easier on the GPUs too.
    if (!isNewBloop) {
        sdf.color = mix(sdf.color, vec4(bloopColors.main, 1.0), args.amount);
        sdf.distance = mix(sdf.distance, length(adjusted_st) - radius, args.amount);
        return sdf;
    }

    // Read color from the prepass texture
    float scaleFactor = 1.0 / (2.0 * radius);
    vec2 uv = (adjusted_st * scaleFactor) + 0.5;
    vec3 orbColor = texture(prepassTexture, uv).rgb;

    float t = mod(args.time * 2.5, 1.0);
    vec2 sampleUv = uv;
    vec2 flowThinking = (texture(flowmapThinking, uv).xy - vec2(0.5)) * 2.0;

    vec2 flowDispThinking = vec2(0.125);
    vec3 sample1Thk = texture(prepassTexture, sampleUv + flowThinking * (flowDispThinking * (t + 1.0))).rgb;
    vec3 sample2Thk = texture(prepassTexture, sampleUv + flowThinking * (flowDispThinking * t)).rgb;
    vec3 thinkingSample = mix(sample1Thk, sample2Thk, t);

    vec3 color = mix(orbColor, thinkingSample, thinkingState);

    vec2 circleUv = uv - vec2(0.5);
    float dist = sqrt(dot(circleUv, circleUv));
    float whiteOverlay = pow(
        smoothstep(0.55 * min(thinkingState, 1.0), 0.0, dist),
        2.0
    ) * min(thinkingState * thinkingState, 1.0);
    color = blendOverlay_9_12(color, vec3(1.0, 0.992, 0.937), whiteOverlay);

    sdf.color = mix(sdf.color, vec4(color, 1.0), args.amount);

    // Blend the distance with the existing sdf distance
    sdf.distance = mix(sdf.distance, length(adjusted_st) - radius, args.amount);

    return sdf;
}

ColoredSDF applySpeakState(ColoredSDF sdf, SDFArgs args, vec4 avgMag, float silenceAmount, float silenceDuration) {
    float d = 1000.0;
    int barCount = 4;
    for (int i = 0; i < barCount; i++) {
        float f = float(float(i) + 0.5) / float(barCount); // fraction

        // height/width of bar
        float w = (1.0 / float(barCount)) * 0.44;
        float h = w;

        // wave-like motion when starting
        float wave = sin(f * PI * 0.8 + args.time) * 0.5 + 0.5;
        float entryAnimation = spring(scaled(0.1 + wave * 0.4, 1.0 + wave * 0.4, args.duration), 0.98);
        vec2 pos = vec2((f) - 0.5, 0.0) * args.mainRadius * 1.9;
        pos.y = 0.25 * (1.0 - entryAnimation);

        // little waddle when silent
        if (silenceAmount > 0.0) {
            float bounceStagger = f/5.0;
            float bounceDelay = 0.6;
            float bounceTimer = scaled(bounceDelay, bounceDelay + 1.0, fract((silenceDuration + bounceStagger) / 2.0) * 2.0);
            pos.y += bounce(bounceTimer, 6.0) * w * 0.25 * silenceAmount * pow(entryAnimation, 4.0) * pow(args.amount, 4.0); // little wiggle
        }

        // audio level for bar
        h += avgMag[i] * (0.1 + (1.0 - abs(f - 0.5) * 2.0) * 0.1);

        float dd = sdRoundedBox(args.st - pos, vec2(w, h), vec4(w));
        d = opSmoothUnion(d, dd, 0.2 * (1.0 - args.amount));
    }

    sdf.distance = mix(sdf.distance, d, args.amount);
    sdf.color = mix(sdf.color, args.displayColor, args.amount);
    return sdf;
}

// Apply Halt State Function
ColoredSDF applyHaltState(ColoredSDF sdf, SDFArgs args) {
    // Animate in then pulse slowly
    float radius = mix(0.4, mix(0.4, 0.45, args.amount), sin(args.time * 0.25) * 0.5 + 0.5);
    float strokeWidth = mix(radius / 2.0, 0.02, args.amount);

    // Stroke position inside
    radius -= strokeWidth;

    radius *= mix(0.7, 1.0, args.amount);
    float circle = abs(length(args.st) - radius) - strokeWidth;

    sdf.distance = mix(sdf.distance, circle, args.amount);
    sdf.color.a = mix(sdf.color.a, pow(0.8, 2.2), scaled(0.5, 1.0, args.amount));
    return sdf;
}

ColoredSDF applyThinkState(ColoredSDF sdf, SDFArgs args) {
    float d = 1000.0; // default value for distance field
    int count = 5;  // cloud bubbles
    float entryAnimation = spring(scaled(0.0, 1.0, args.duration), 1.0);

    float thinkingDotEntryAnimation = spring(scaled(0.1, 1.1, args.duration), 1.0);
    float thinkingDotRadius = mix(0.2, 0.06, thinkingDotEntryAnimation) * args.amount;

    // move coordinate system to align with optical center
    args.st.x -= thinkingDotRadius * 0.5 * thinkingDotEntryAnimation;

    for (int i = 0; i < count; i++) {
        float f = float(float(i) + 0.5) / float(count); // fraction
        float a = -f * PI * 2.0 + args.time / 3.0 + spring(scaled(0.0, 10.0, args.duration), 1.0) * PI / 2.0;
        float ringRadi = args.mainRadius * 0.45 * entryAnimation;

        // cloud pattern
        ringRadi -= (sin(entryAnimation * PI * 4.0 + a * PI * 2.0 + args.time * 3.0 -silkySmooth(args.time / 4.0, 2.0) * PI * 1.0) * 0.5 + 0.5) * args.mainRadius * 0.1;

        vec2 pos = vec2(cos(a), sin(a)) * ringRadi;
        float dd = length(args.st - pos) - args.mainRadius * 0.5;

        // last value here is "goo"-level
        d = opSmoothUnion(d, dd, 0.03 * (scaled(0.0, 10.0, args.duration)) + 0.8 * (1.0 - entryAnimation));

        // thinking dot
        float dotAngle = f * PI * 2.0;
        float dotRingRadius = (sin(thinkingDotEntryAnimation * PI * 4.0 + a * PI * 2.0 + args.time * 0.1 * PI * 4.0) * 0.5 + 0.5) * thinkingDotRadius * 0.3;
        vec2 dotPos = vec2(-args.mainRadius, args.mainRadius) * 0.8 * thinkingDotEntryAnimation;
        vec2 dotOffset = vec2(cos(dotAngle + args.time), sin(dotAngle + args.time)) * dotRingRadius;
        float dotD = length(args.st - dotPos - dotOffset) - thinkingDotRadius * 0.8;
        d = opSmoothUnion(d, dotD, (1.0 - min(thinkingDotEntryAnimation, args.amount)) * thinkingDotRadius);
    }
    sdf.distance = mix(sdf.distance, d, args.amount);
    sdf.color.a = 1.0;

    return sdf;
}

ColoredSDF applyListenState(ColoredSDF sdf, SDFArgs args, float micLevel) {
    float breathingSequence = sin(args.time) * 0.5 + 0.5;
    float entryAnimation = fixedSpring(scaled(0.0, 3.0, args.duration), 0.9);

    float listenAnimation = clamp(spring(scaled(0.0, 0.9, args.duration), 1.0), 0.0, 1.0);
    float radius = 0.0;
    float smoothlevel = micLevel;
    float l1 = smoothlevel;
    radius = 0.38 + l1 * 0.05 + breathingSequence * 0.03;
    radius *= 1.0 - (1.0 - entryAnimation) * 0.25;

    float d = length(args.st) - radius;

    sdf.distance = mix(sdf.distance, d, args.amount);

    // Reduce alpha when mic power goes up
    sdf.color.a = mix(sdf.color.a, mix(1.0, 1.0 - l1 * 0.6, listenAnimation), args.amount);

    return sdf;
}

// Main Fragment Shader
void main() {
    vec2 st = out_uv - 0.5;

    BloopColors bloopColors;
    bloopColors.main = bloopColorsMain;
    bloopColors.low = bloopColorsLow;
    bloopColors.mid = bloopColorsMid;
    bloopColors.high = bloopColorsHigh;

    ColoredSDF sdf;
    sdf.distance = 1000.0;
    sdf.color = vec4(baseColor, 1.0);

    SDFArgs args;
    args.st = st;
    args.time = time;
    args.mainRadius = 0.49;
    args.displayColor = vec4(baseColor, 1.0);

    SDFArgs idleArgs = args;
    SDFArgs listenArgs = args;
    SDFArgs thinkArgs = args;
    SDFArgs speakArgs = args;
    SDFArgs haltArgs = args;
    SDFArgs failedToConnectArgs = args;

    idleArgs.amount = 1.0;
    listenArgs.amount = stateListen;
    thinkArgs.amount = stateThink;
    speakArgs.amount = stateSpeak;
    haltArgs.amount = stateHalt;
    failedToConnectArgs.amount = stateFailedToConnect;

    idleArgs.duration = time - readyTimestamp;
    listenArgs.duration = time - listenTimestamp;
    thinkArgs.duration = time - thinkTimestamp;
    speakArgs.duration = time - speakTimestamp;
    haltArgs.duration = time - haltTimestamp;
    failedToConnectArgs.duration = time - failedToConnectTimestamp;

    sdf = applyIdleState(sdf, idleArgs);

    // Failed state
    if (failedToConnectArgs.amount > 0.0) {
        sdf = applyHaltState(sdf, failedToConnectArgs);
    }

    //  Listen state
    if (listenArgs.amount > 0.0) {
        if (isNewBloop) {
            if (thinkArgs.amount > 0.0 || speakArgs.amount > 0.0) {
                listenArgs.amount = 1.0;
            }
            sdf = applyListenAndSpeakState(
                sdf,
                listenArgs,
                micLevel,
                avgMag,
                cumulativeAudio,
                bloopColors,
                prepassTexture,
                flowmapThinking,
                thinkArgs.amount,
                true,
                isNewBloop);
        } else {
            sdf = applyListenState(sdf, listenArgs, micLevel);
        }
    }

    // Think
    if (thinkArgs.amount > 0.0) {
        if (isNewBloop) {
            if (speakArgs.amount > 0.0) {
                thinkArgs.amount = 1.0;
            }
            sdf = applyListenAndSpeakState(
                sdf,
                thinkArgs,
                micLevel,
                avgMag,
                cumulativeAudio,
                bloopColors,
                prepassTexture,
                flowmapThinking,
                thinkArgs.amount,
                true,
                isNewBloop
            );
        } else {
            sdf = applyThinkState(sdf, thinkArgs);
        }
    }

    // Speak state
    if (speakArgs.amount > 0.0) {
        if (isNewBloop) {
            sdf = applyListenAndSpeakState(
                sdf,
                speakArgs,
                micLevel,
                avgMag,
                cumulativeAudio,
                bloopColors,
                prepassTexture,
                flowmapThinking,
                thinkArgs.amount,
                false,
                isNewBloop);
        } else {
            float silenceDuration = time - silenceTimestamp;
            sdf = applySpeakState(sdf, speakArgs, avgMag, silenceAmount, silenceDuration);
        }
    }

    // Halt state
    if (haltArgs.amount > 0.0) {
        sdf = applyHaltState(sdf, haltArgs);
    }

    // edge sharpness, higher value, blurrier border
    // practically free anti-aliasing
    float clampingTolerance = 0.005;

    // Clamp the edge of the signed distance field
    float clampedShape = smoothstep(clampingTolerance, 0.0, sdf.distance);
    float alpha = sdf.color.a * clampedShape;
    if (!isNewBloop) {
        alpha *= scaled(0.0, 1.0, time);
    }
    FragColor = vec4(sdf.color.rgb, alpha);
}
