# Native dependencies

The engine source is independent from any application framework. Consumers
provide these native dependencies at build time:

| Dependency | Validated version | Purpose |
| --- | --- | --- |
| OpenCV | 4.12.0 | Image decode, geometry, warping, thresholding and bubble analysis |
| ZXing-C++ | 3.1.0 | QR decoding |
| CMake | 3.22.1 or newer | Android/iOS/native build orchestration |
| C++ | C++20 | Portable core implementation and ZXing 3.1 compatibility |

For Android, set:

- `OMR_OPENCV_INCLUDE_DIR` to the OpenCV Android headers;
- `OMR_OPENCV_SHARED_DIR` to the directory containing ABI folders such as
  `arm64-v8a/libopencv_java4.so`;
- `OMR_ZXING_SOURCE_DIR` to a ZXing-C++ source checkout.

For host or iOS toolchains, OpenCV and ZXing may instead be provided as CMake
packages discoverable by `find_package`.
