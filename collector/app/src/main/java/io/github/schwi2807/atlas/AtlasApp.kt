package io.github.schwi2807.atlas

import android.app.Application

class AtlasApp : Application() {
    val survey by lazy { SurveyController(this) }
}
