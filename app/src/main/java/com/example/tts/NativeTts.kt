package com.example.tts

object NativeTts {
    init {
        System.loadLibrary("onnxruntime")
        System.loadLibrary("sherpa-onnx-c-api")
        System.loadLibrary("native-lib")
    }

    external fun setEngineConfig(provider: String, numThreads: Int): Boolean
    external fun setProsodyConfig(
        lengthScale: Float,
        noiseScale: Float,
        silenceScale: Float
    ): Boolean
    external fun init(
        acousticModelPath: String,
        vocoderPath: String,
        tokensPath: String,
        lexiconPath: String,
        dataDir: String
    ): Boolean
    external fun runtimeProvider(): String
    external fun runtimeThreads(): Int
    external fun warmup(speed: Float = 1.35f): Boolean
    external fun generate(text: String, speed: Float): FloatArray
    external fun sampleRate(): Int
    external fun release()
}
