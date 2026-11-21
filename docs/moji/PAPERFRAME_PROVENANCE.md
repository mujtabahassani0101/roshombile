# Component provenance

The Flutter UI, JNI bridge, boundary fitting, tracking and dense-map processing are project code. Models have not been independently trained.

## MNN 3.6.1

- Upstream: https://github.com/alibaba/MNN/releases/tag/3.6.1
- Official Android release archive: `mnn_3.6.1_android_armv7_armv8_cpu_opencl_vulkan.zip`
- Archive SHA-256, checked against GitHub release asset digest: `46dc7e86d45b8d4e957db81d2603e0b7f6c9ce9b84092ffdcee1b843cbfc9d71`
- ARM64 `libMNN.so` SHA-256: `c91fb9e65ef45477406583cf374978c61713318893f6af473aecdafed4c17f60`
- Headers are from upstream tag `3.6.1/include/MNN`.
- Apache License 2.0: `android/app/src/main/cpp/MNN-LICENSE.txt`.
- Only CPU inference is used. GPU, cloud, LLM and audio components are not packaged.

## Scanner model data from the supplied APK

Source: user-provided CamScanner `com.intsig.camscanner` 7.27.0.2609220000 ARM64 split, `libpagescanner.so` SHA-256 `d0add13c1132ee5c0af58ea2845b9282d62348583ca50831e57720c8dcf288a2`.

| Packaged asset | ELF file offset | Length | Purpose |
|---|---:|---:|---|
| `scanner/border.mnn` | `0x3cd920` | 1,584,816 | Learned boundary heatmap |
| `scanner/dewarp.mnn` | `0x61fa60` | 1,584,744 | Default InitDewarpContext displacement network |

These are extracted original weights for this user's local reconstruction. Their inclusion is not a claim of original training, open-source licensing, or a grant of redistribution rights. MNN's Apache license applies to the runtime and headers, not to these model weights. The original complete scanner libraries remain analysis-only and are not packaged in the app.

## Quality-test images (development only)

- Real curved manual: https://github.com/cvlab-stonybrook/DewarpNet/blob/master/eval/inp/1_1%20copy.png — Das et al., *DewarpNet*, ICCV 2019.
- Real folded/shadowed leaflet: https://github.com/cvlab-stonybrook/DocIIW/blob/master/testimgs/real/1.png — Das et al., *Intrinsic Decomposition of Document Images In-the-Wild*, BMVC 2020.
- These research inputs are not release assets. Generated Dart fixture data is local under `analysis/deep`.

OpenCV, Flutter and pub packages retain their upstream licenses, included through their normal dependency distributions.
