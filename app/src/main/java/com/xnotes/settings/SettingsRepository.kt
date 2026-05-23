package com.xnotes.settings

import android.content.Context
import com.xnotes.platform.JsonStore

/** Loads/saves [Settings] via the atomic, failure-tolerant [JsonStore]. */
class SettingsRepository(context: Context) {
    private val store = JsonStore.settings(context.applicationContext)

    fun load(): Settings = Settings.fromJson(store.read())

    fun save(settings: Settings) = store.write(settings.toJson())
}
