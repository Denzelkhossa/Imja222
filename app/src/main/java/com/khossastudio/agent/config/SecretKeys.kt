package com.khossastudio.agent.config

import com.khossastudio.agent.BuildConfig

object SecretKeys {
    val defaultGeminiKeys: List<String> by lazy {
        listOf(
            BuildConfig.GEMINI_KEY_1,
            BuildConfig.GEMINI_KEY_2,
            BuildConfig.GEMINI_KEY_3,
            BuildConfig.GEMINI_KEY_4,
            BuildConfig.GEMINI_KEY_5
        ).filter { key -> key.isNotBlank() }
    }
    
    val defaultNvidiaKeys: List<String> by lazy {
        listOf(
            BuildConfig.NVIDIA_KEY_1
        ).filter { key -> key.isNotBlank() }
    }
}
