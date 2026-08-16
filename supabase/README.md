# Supabase Configuration

Copy this file to `supabase/Config.kt` and fill in your values.

## Get your credentials

1. Go to https://supabase.com
2. Create a new project or select existing one
3. Go to Settings > API
4. Copy your Project URL and anon public key

```kotlin
package com.example.deskpet.util

object SupabaseConfig {
    const val SUPABASE_URL = "https://your-project-id.supabase.co"
    const val SUPABASE_ANON_KEY = "your-anon-key-here"

    // API endpoints
    const val REST_ENDPOINT = "$SUPABASE_URL/rest/v1"
    const val REALTIME_ENDPOINT = "$SUPABASE_URL/realtime/v1"
}
```

## Security Notes

- **NEVER** commit your keys to a public repository
- Use `secrets.properties` and build config fields for production
- Enable Row Level Security (RLS) on all tables
- The anon key is safe for client-side use with proper RLS policies
