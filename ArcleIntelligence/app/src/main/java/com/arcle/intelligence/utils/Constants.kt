package com.arcle.intelligence.utils

object Constants {

    // App Info
    const val APP_NAME = "Arcle Intelligence"
    const val APP_VERSION = "2.0.0"
    const val DEVELOPER_NAME = "Abhinav Anand"

    // Model Asset Paths (relative to assets/models/)
    const val MODEL_QWEN3_VL_PATH       = "models/qwen3_vl/"
    const val MODEL_SDXS_PATH           = "models/sdxs512/"
    const val MODEL_SHERPA_KWS_PATH     = "models/sherpa_kws/"
    const val MODEL_SHERPA_STT_PATH     = "models/sherpa_stt/"
    const val MODEL_SHERPA_TTS_PATH     = "models/sherpa_tts/"
    const val MODEL_YOLO_PATH           = "models/yolo11n/"
    const val MODEL_T2V_PATH            = "models/mobile_t2v/"

    // Wake Words
    val WAKE_WORDS = listOf("hey arcle", "ok arcle", "okay arcle", "wake up arcle", "arcle")

    // LLM Settings
    const val LLM_MAX_TOKENS            = 2048
    const val LLM_CONTEXT_WINDOW        = 20
    const val LLM_TEMPERATURE           = 0.7f

    // Object Recognition
    const val YOLO_CONFIDENCE_THRESHOLD = 0.70f

    // Code Output Directory
    const val CODE_OUTPUT_DIR = "ArcleIntelligence/CodeOutput"

    // Reminder Check on First Command
    const val REMINDER_CHECK_ON_STARTUP = true

    // Internet Logic Flow
    const val INTERNET_WAIT_TIMEOUT_MS  = 3000L
    const val INTERNET_POLL_INTERVAL_MS = 1000L

    // ── Deep Research Engine ──────────────────────────────────────────────────
    const val DEEP_RESEARCH_MAX_SITES       = 200
    const val DEEP_RESEARCH_PAGE_TIMEOUT_MS = 30000L
    const val DEEP_RESEARCH_MAX_RETRIES     = 5
    const val DEEP_RESEARCH_RETRY_DELAY_MS  = 5000L
    const val DEEP_RESEARCH_SEARCH_DELAY_MS = 2000L
    const val DEEP_RESEARCH_EXTRACT_DELAY_MS = 1500L
    const val DEEP_RESEARCH_MAX_CHARS_PER_PAGE = 5000

    // ── Emotional State Tracking ──────────────────────────────────────────────
    const val EMOTION_HISTORY_SIZE          = 10
    const val EMOTION_UPDATE_EVERY_N_MSGS   = 3
    const val EMOTION_CONFIDENCE_THRESHOLD  = 0.6f

    // ── Telemetry Batch Manager (Supabase) ───────────────────────────────────
    const val SUPABASE_URL                  = "https://ushvihghxlgvenxezixc.supabase.co"
    const val SUPABASE_ANON_KEY             = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InVzaHZpaGdoeGxndmVueGV6aXhjIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzI5OTk5MjgsImV4cCI6MjA4ODU3NTkyOH0.Y9vZjk17gmN6n8c31JqAnbzZ2BvoovsIwLcLi3c9ws4"
    const val SUPABASE_TABLE_NAME           = "arcle_batches"

    const val TELEMETRY_BATCH_SIZE          = 20
    const val TELEMETRY_UPLOAD_CHUNK_SIZE   = 5
    const val TELEMETRY_UPLOAD_DELAY_MS     = 3000L
    const val TELEMETRY_MAX_STORED_BATCHES  = 50
}
