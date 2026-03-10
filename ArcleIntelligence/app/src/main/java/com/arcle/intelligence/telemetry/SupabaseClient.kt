package com.arcle.intelligence.telemetry

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import com.arcle.intelligence.utils.Constants

/**
 * Supabase Client Provider — singleton for telemetry batch upload.
 * This is the ONLY external network dependency in the entire app.
 */
object SupabaseClientProvider {

    val client by lazy {
        createSupabaseClient(
            supabaseUrl = Constants.SUPABASE_URL,
            supabaseKey = Constants.SUPABASE_ANON_KEY
        ) {
            install(Postgrest)
        }
    }
}
