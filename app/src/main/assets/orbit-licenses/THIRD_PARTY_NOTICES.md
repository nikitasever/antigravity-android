# Third party dependencies

The SDK uses Metal on Apple platforms and OpenGL ES 3.0 on Android. It does not include a third party rendering engine. This inventory describes source and package contents. It is not approval for another product or distribution channel.

| Dependency | Version | License and use |
| --- | --- | --- |
| Khronos PBR Neutral tone mapping | [Pinned source](third_party/khronos/source.lock.json) | The small tone mapping function is adapted in both shaders. [Apache 2.0](third_party/khronos/LICENSE.txt), with [attribution and modifications](third_party/khronos/NOTICE.txt). |
| OpenLibm / fdlibm geometry angles | [Pinned source](third_party/openlibm/source.lock.json) | Only the double-precision atan/atan2 kernels are adapted for identical graphic-eye normals and crescent socket angles across clients. [Sun permission notice](third_party/openlibm/LICENSE.txt), with [attribution and modifications](third_party/openlibm/NOTICE.txt). |
| System zlib | Platform supplied | Resource compression and decompression. No zlib source or static archive is vendored. |
| Android NDK LLVM C++ runtime | 29.0.14206865 | Statically linked in Android packages. [Runtime notices](third_party/android-runtime/NDK-r29-LLVM-NOTICE.txt) preserve libc++, libc++abi, and LLVM exceptions. |

Filament, its material compiler, and its support libraries are absent from the current build and packages. The engine does not distribute Abseil, Zstandard, SMOL-V, meshoptimizer, MikkTSpace, Vulkan Memory Allocator, robin-map, Earcut, or nlohmann/json. It does not copy Filament's FXAA, FSR, SGSR, or reflection implementation.

Metal libraries are compiled for each Apple platform and embedded in its native archive. GLSL source is embedded in the Android library. The build records canonical source, generation scripts, compiler configuration, and payload hashes. Shader loading never depends on a path from the build machine.

Source SVGs and reference artwork come from the project's Figma design.

## Three.js (Felipe fur study)

The native Felipe study uses the Three.js 0.179.1 area light lookup tables, area light integration, and ACES display transform.

The MIT License

Copyright © 2010-2025 three.js authors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
