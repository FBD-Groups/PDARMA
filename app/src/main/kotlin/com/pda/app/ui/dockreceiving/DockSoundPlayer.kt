package com.pda.app.ui.dockreceiving

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import com.pda.app.R
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** 对齐 RMA web：SUCCESS.wav / BEEP.wav。 */
interface DockSoundPlayer {
    fun playSuccess()
    fun playBeep()
}

@Singleton
class AndroidDockSoundPlayer @Inject constructor(
    @ApplicationContext context: Context
) : DockSoundPlayer {

    companion object {
        private const val TAG = "PDA/DockSoundPlayer"
    }

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val successId: Int = pool.load(context, R.raw.success, 1)
    private val beepId: Int = pool.load(context, R.raw.beep, 1)

    override fun playSuccess() = play(successId)

    override fun playBeep() = play(beepId)

    private fun play(soundId: Int) {
        try {
            pool.play(soundId, 1f, 1f, 1, 0, 1f)
        } catch (e: Exception) {
            Log.w(TAG, "play failed: ${e.message}")
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DockSoundPlayerModule {
    @Binds
    @Singleton
    abstract fun bindDockSoundPlayer(impl: AndroidDockSoundPlayer): DockSoundPlayer
}
