package com.akenory.personalka.android

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.akenory.personalka.domain.sync.SyncRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Обмен с сервером раз в 15 минут при закрытом приложении (ТЗ 7.2, п. 4; шаг 2.10а): задача JobScheduler с условием
 * «есть сеть», переживает перезагрузку телефона. Пока процесс жив, обмен ведёт `SyncRunner` в [PersonalkaApplication];
 * задача зовёт тот же `SyncRepository.sync()`, обмены идут по одному. Сеть пропала посреди обмена — Android повторит
 * задачу сам.
 */
class SyncJob : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext as PersonalkaApplication
        work = CoroutineScope(Dispatchers.Default).launch {
            try {
                app.sync.sync()
            } catch (_: Exception) {
                // База занята восстановлением копии — обменяемся в следующий раз.
            }
            // Ошибка связи повторится через 15 минут — следующим запуском задачи.
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        // Условие пропало (нет сети) — повторить, когда вернётся.
        return true
    }

    companion object {
        private const val ID = 2_010

        /** Сервер подключён — задача стоит (уже стоящая не переставляется, её период не сбивается); отключён — снята. */
        fun schedule(context: Context, connected: Boolean) {
            val jobs = context.getSystemService(JobScheduler::class.java) ?: return
            if (!connected) {
                jobs.cancel(ID)
                return
            }
            if (jobs.getPendingJob(ID) != null) return
            val job = JobInfo.Builder(ID, ComponentName(context, SyncJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(SyncRules.PERIOD_MS)
                .setPersisted(true)
                .build()
            // Отказ системы (разрешения, предел задач) не роняет приложение: пока оно открыто, обмен ведёт SyncRunner.
            runCatching { jobs.schedule(job) }
        }
    }
}
