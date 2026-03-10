ArcleIntelligence — Native Libraries (jniLibs/arm64-v8a)
═══════════════════════════════════════════════════════

This folder should contain the following .so native libraries
for 64-bit ARM (arm64-v8a) architecture:

┌─────────────────────────────────────┬─────────────────────────────────────┐
│ Library File                         │ Purpose                             │
├─────────────────────────────────────┼─────────────────────────────────────┤
│ libsherpa-onnx-jni.so              │ Sherpa-ONNX JNI bridge              │
│ libsherpa-onnx-core.so             │ Sherpa-ONNX core (KWS, STT, TTS)   │
│ libonnxruntime.so                   │ ONNX Runtime inference engine       │
│ libkaldi-native-fbank-jni.so       │ Audio feature extraction (Fbank)    │
└─────────────────────────────────────┴─────────────────────────────────────┘

Download sources:
  • Sherpa-ONNX: https://github.com/k2-fsa/sherpa-onnx/releases
  • ONNX Runtime: https://github.com/microsoft/onnxruntime/releases

Steps:
  1. Download the sherpa-onnx Android release AAR or pre-built .so files
  2. Download onnxruntime-android AAR (arm64-v8a)
  3. Extract the .so files and place them in this folder
  4. Build the project — Gradle will automatically package these into the APK

NOTE: Without these .so files, the app will compile but crash at runtime
when attempting to load native libraries.
