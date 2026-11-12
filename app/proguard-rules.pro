# Reglas de R8 para Estante de Pinturas.
# ML Kit, CameraX y Compose traen sus propias reglas; aquí solo lo que falte.
-dontwarn com.google.android.gms.**
-dontwarn com.google.mlkit.**

# ONNX Runtime usa JNI y reflexión
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
