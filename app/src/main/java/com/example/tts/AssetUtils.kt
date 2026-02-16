package com.example.tts

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

data class MatchaPaths(
    val acousticModelPath: String,
    val vocoderPath: String,
    val tokensPath: String,
    val lexiconPath: String,
    val dataDir: String
)

object AssetUtils {
    private const val ASSET_ROOT = "matcha"
    private const val TAG = "MatchaTts"

    fun ensureMatchaAssets(context: Context): MatchaPaths {
        val outRoot = File(context.filesDir, ASSET_ROOT)
        copyAssetRecursive(context, ASSET_ROOT, outRoot)

        val tokens = File(outRoot, "tokens.txt")
        val dataDir = File(outRoot, "espeak-ng-data")

        val onnxFiles = outRoot.listFiles { file ->
            file.isFile && file.name.endsWith(".onnx", ignoreCase = true)
        }?.toList().orEmpty()

        val vocoder = firstExisting(
            outRoot,
            listOf("vocoder.onnx", "vocos-22khz-univ.onnx", "hifigan.onnx", "vocos.onnx")
        ) ?: onnxFiles.firstOrNull {
            val name = it.name.lowercase()
            name.contains("vocoder") || name.contains("hifigan") || name.contains("vocos")
        }

        val acoustic = firstExisting(
            outRoot,
            listOf("acoustic_model.onnx", "model.onnx", "matcha.onnx", "model-steps-3.onnx")
        ) ?: onnxFiles.firstOrNull {
            it != vocoder && !it.name.lowercase().contains("vocoder")
        }

        val lexicon = firstExisting(
            outRoot,
            listOf("lexicon.txt", "lexicon-us-en.txt", "lexicon-en.txt")
        ) ?: outRoot.listFiles { file ->
            file.isFile && file.name.startsWith("lexicon", ignoreCase = true)
        }?.firstOrNull()

        require(acoustic != null && acoustic.exists()) {
            "Missing Matcha acoustic model (.onnx) in ${outRoot.absolutePath}"
        }
        require(vocoder != null && vocoder.exists()) {
            "Missing Matcha vocoder model (.onnx) in ${outRoot.absolutePath}"
        }
        require(tokens.exists()) { "Missing ${tokens.absolutePath}" }
        require(dataDir.exists()) { "Missing ${dataDir.absolutePath}" }

        Log.i(TAG, "Using acoustic model: ${acoustic.absolutePath}")
        Log.i(TAG, "Using vocoder model: ${vocoder.absolutePath}")
        if (lexicon != null && lexicon.exists()) {
            Log.i(TAG, "Using lexicon: ${lexicon.absolutePath}")
        } else {
            Log.i(TAG, "No lexicon file found. Proceeding without explicit lexicon path.")
        }

        return MatchaPaths(
            acousticModelPath = acoustic.absolutePath,
            vocoderPath = vocoder.absolutePath,
            tokensPath = tokens.absolutePath,
            lexiconPath = lexicon?.absolutePath ?: "",
            dataDir = dataDir.absolutePath
        )
    }

    private fun firstExisting(root: File, names: List<String>): File? {
        for (name in names) {
            val f = File(root, name)
            if (f.exists()) return f
        }
        return null
    }

    private fun copyAssetRecursive(context: Context, assetPath: String, outFile: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            copyAssetFile(context, assetPath, outFile)
            return
        }

        if (!outFile.exists()) {
            outFile.mkdirs()
        }

        for (child in children) {
            copyAssetRecursive(context, "$assetPath/$child", File(outFile, child))
        }
    }

    private fun copyAssetFile(context: Context, assetPath: String, outFile: File) {
        if (outFile.exists() && outFile.length() > 0L) return

        outFile.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
    }
}
