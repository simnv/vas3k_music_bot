package dev.storozhenko.music.run

import dev.storozhenko.music.RequestOptions
import dev.storozhenko.music.getLogger
import dev.storozhenko.music.parseRequestOptions
import dev.storozhenko.music.services.DownloadService
import dev.storozhenko.music.services.ErrorNotificationService
import dev.storozhenko.music.services.MediaProbeService
import dev.storozhenko.music.services.MediaProcessingService
import dev.storozhenko.music.services.MusicSearchService
import dev.storozhenko.music.services.LinkMessageBuilder
import dev.storozhenko.music.services.OdesilService
import dev.storozhenko.music.services.TelegramSender
import dev.storozhenko.music.services.UrlValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.CallbackQuery
import org.telegram.telegrambots.meta.api.objects.LinkPreviewOptions
import org.telegram.telegrambots.meta.api.objects.MessageEntity
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

class Bot(
    private val botName: String,
    private val ytdlLocation: String,
    private val telegramClient: TelegramClient,
    private val telegramAllowList: String,
    private val errorNotificationTelegramId: String?,
    private val ipv6UrlContains: String?,
    private val chunkSizeMB: Int,
    private val ytdlProxy: String?,
    private val ytdlProxyUrlContains: String?,
    private val jobMarkerDir: String = "/data/jobs",
    private val maxConcurrentDownloads: Int = 4,
    private val maxConcurrentDownloadsPerChat: Int = 2,
) : LongPollingUpdateConsumer {
    private val logger = getLogger()
    private val errorNotificationService = errorNotificationTelegramId?.let { ErrorNotificationService(telegramClient, it) }
    private val musicSearchService = MusicSearchService()
    private val odesilService = OdesilService(musicSearchService)
    private val urlValidator = UrlValidator()
    private val linkBuilder = LinkMessageBuilder()
    private val downloader: DownloadService
    private val probe: MediaProbeService
    private val processor: MediaProcessingService
    private val sender: TelegramSender
    private val pipeline: DownloadPipeline
    val handler = CoroutineExceptionHandler { _, exception ->
        logger.error("Caught exception: $exception")
        errorNotificationService?.sendErrorNotification(exception)
    }
    private val virtualDispatcher = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
    private val coroutine = CoroutineScope(virtualDispatcher + SupervisorJob() + handler)
    private val helpMessage = getResource("help_message.txt")
    private val chatsAndPlaylistNames = telegramAllowList
        .split(",")
        .map { line -> line.split(":") }
        .associate { (id, prefix) -> id.toLong() to prefix }
    private val fileDeleteScope = CoroutineScope(Dispatchers.Default + SupervisorJob() + handler)
    private val processorScope = CoroutineScope(virtualDispatcher + SupervisorJob() + handler)
    private val jobs = JobRegistry()
    private val markerStore = JobMarkerStore(File(jobMarkerDir))
    private val admission = DownloadAdmission(maxConcurrentDownloads, maxConcurrentDownloadsPerChat)

    init {
        downloader = DownloadService(
            ytdlLocation, virtualDispatcher, fileDeleteScope,
            ipv6UrlContains, ytdlProxy, ytdlProxyUrlContains,
            errorNotificationService
        )
        probe = MediaProbeService(virtualDispatcher)
        processor = MediaProcessingService(virtualDispatcher, fileDeleteScope, chunkSizeMB, errorNotificationService)
        sender = TelegramSender(telegramClient, coroutine)
        pipeline = DownloadPipeline(downloader, probe, processor, sender, chunkSizeMB)
    }

    private val orphanSweeper = OrphanSweeper(markerStore, sender)

    suspend fun sweepOrphans() = orphanSweeper.sweep()


    override fun consume(updates: MutableList<Update>) {
        logger.info("Got ${updates.size} updates")
        updates.forEach {
            processorScope.launch {
                runCatching {
                    if (it.hasCallbackQuery()) handleCallback(it.callbackQuery)
                    else consume(it)
                }.onFailure { e ->
                    if (e is CancellationException) {
                        logger.info("Update processing cancelled: ${e.message}")
                    } else {
                        logger.error("Can not process update $it", e)
                        errorNotificationService?.sendUpdateProcessingErrorNotification(e)
                    }
                }
            }
        }
    }

    private suspend fun handleCallback(query: CallbackQuery) {
        val data = query.data ?: return
        if (!data.startsWith("cancel:")) return
        val token = data.substringAfter("cancel:")
        val cancellable = jobs.take(token)
        val text = if (cancellable != null) {
            val from = query.from
            val clickerName = from?.let { listOfNotNull(it.firstName, it.lastName).joinToString(" ").ifBlank { null } }
            val clickerUsername = from?.userName
            errorNotificationService?.sendCancellationNotification(
                clickerName, clickerUsername,
                cancellable.chatTitle, cancellable.chatId, cancellable.originalMessageId,
                cancellable.url,
            )
            cancellable.job.cancel(CancellationException("user cancelled via button"))
            "Отменено"
        } else {
            query.message?.let { msg ->
                runCatching { sender.removeKeyboard(msg.chatId, msg.messageId) }
            }
            "Уже завершено"
        }
        runCatching {
            telegramClient.executeAsync(
                AnswerCallbackQuery.builder().callbackQueryId(query.id).text(text).build()
            ).await()
        }
    }

    private suspend fun consume(update: Update) {
        if (!update.hasMessage() || !update.message.hasText()) {
            logger.info("Got an update without text")
            return
        }

        val chatId = update.message.chatId
        if (chatId !in chatsAndPlaylistNames) {
            logger.info("Got an update from unauthorized chat $chatId")
            return
        }

        if (!update.message.hasEntities()) {
            logger.info("Got an update with no entities")
            return
        }

        val entities = update.message.entities
        val command = getCommand(entities)

        if (command != null) {
            logger.info("Processing command $command")
            coroutine.launch {
                runCatching {
                    processCommands(update, command)
                }.onFailure {
                    logger.error("Can't process command for $update", it)
                    errorNotificationService?.sendCommandErrorNotification(it, command)
                }
            }
            return
        }

        val urlEntities = entities.filter { entity -> entity.type == "url" }
        if (urlEntities.isEmpty()) {
            logger.info("No URL entities, returning")
            return
        }

        handleUrlMessage(update, urlEntities)
    }

    private data class LinkResolution(val message: String, val downloadUrl: String?)

    private suspend fun handleUrlMessage(update: Update, urlEntities: List<MessageEntity>) {
        val chatId = update.message.chatId
        val validLinks = urlEntities.filter { entity -> urlValidator.isValidDownloadUrl(entity.text) }
        val (quality, forceAudio) = parseRequestOptions(update.message.text)
        val prefetchUrl = validLinks.firstOrNull()?.text

        val isKnownMusic = validLinks.isNotEmpty() || urlEntities.any { linkBuilder.isKnownOdesliMusicUrl(it.text) }
        var pulser: TelegramSender.ChatActionPulser? = if (isKnownMusic) sender.startPulser(chatId, "typing") else null
        val cancelToken = UUID.randomUUID().toString()
        val chatTitle = update.message.chat.title ?: "Private Chat"
        val originalUrl = validLinks.firstOrNull()?.text ?: urlEntities.firstOrNull()?.text
        jobs.register(
            cancelToken,
            CancellableJob(
                job = requireNotNull(currentCoroutineContext()[Job]) { "no Job in coroutine context" },
                url = originalUrl,
                chatId = chatId,
                chatTitle = chatTitle,
                originalMessageId = update.message.messageId,
            )
        )
        val cancelKb = jobs.cancelKeyboard(cancelToken)
        val replyToMessageId = update.message.getMessageId()
        var tmId: Int? = null
        var prefetchedDownload: Deferred<File?>? = null
        try {
            admission.withSlot<Unit>(
                chatId,
                onWait = {
                    if (validLinks.isNotEmpty()) {
                        tmId = sendStatusMessage(chatId, "⏳ In queue...", replyToMessageId, cancelKb, cancelToken, originalUrl)
                    }
                },
            ) {
                // Prefetch starts only once we hold a slot, so queued jobs don't spawn yt-dlp early.
                prefetchedDownload = prefetchUrl?.let { url ->
                    val flags = if (forceAudio) downloader.audioFlags(url) else downloader.videoFlags(url, quality.formatSelector)
                    coroutine.async { downloader.download("${UUID.randomUUID()}.%(ext)s", url, *flags.toTypedArray()) }
                }

                // Send a "Downloading..." placeholder right away when we have a downloadable URL,
                // BEFORE Odesli detect, so the user sees feedback within ~0.5s instead of ~1-22s.
                if (validLinks.isNotEmpty()) {
                    val queuedId = tmId
                    if (queuedId == null) {
                        tmId = sendStatusMessage(chatId, "Downloading...", replyToMessageId, cancelKb, cancelToken, originalUrl)
                    } else {
                        sender.editMessageText(chatId, queuedId, "Downloading...", cancelKb)
                    }
                }

                // Now run Odesli enrichment in parallel with the prefetch download (and the visible placeholder).
                val resolution = resolveLinks(
                    urlEntities, validLinks, chatId,
                    onDetected = {
                        // If the URL host wasn't a known music host but Odesli matched anyway, start pulser now.
                        if (pulser == null) pulser = sender.startPulser(chatId, "typing")
                    },
                ) { partial ->
                    tmId?.let { id ->
                        runCatching { sender.editMessageText(chatId, id, "$partial\nDownloading...", cancelKb) }
                    }
                } ?: run {
                    logger.info("No links from Odesil or valid video services, returning")
                    return@withSlot
                }

                val message = resolution.message
                val requestMode = if (forceAudio) "audio (forced)" else quality.label

                logger.info("Sending message: $message")
                val authorUsername = update.message.from?.userName
                val authorName = update.message.from?.let { listOfNotNull(it.firstName, it.lastName).joinToString(" ").ifBlank { null } }
                errorNotificationService?.sendMessageWithSourceInfo(message, authorName, authorUsername, chatId, update.message.messageId, chatTitle, requestMode)

                val mid: Int = tmId?.also {
                    sender.editMessageText(chatId, it, "$message\nDownloading...", cancelKb)
                } ?: sendStatusMessage(chatId, message, replyToMessageId, cancelKb, cancelToken, originalUrl).also { tmId = it }

                // Prefer the user's posted URL (validLinks[0]) over an Odesli-derived YouTube URL, so the
                // prefetched download is reused instead of being thrown away in favor of a YT redownload.
                val downloadUrl = validLinks.firstOrNull()?.text ?: resolution.downloadUrl ?: return@withSlot
                pipeline.run(
                    DownloadRequest(
                        url = downloadUrl,
                        message = message,
                        statusMessageId = mid,
                        replyToMessageId = replyToMessageId,
                        chatId = chatId,
                        quality = quality,
                        forceAudio = forceAudio,
                        isMusicChat = chatsAndPlaylistNames[chatId]?.contains("music", ignoreCase = true) == true,
                        prefetchedUrl = prefetchUrl,
                        prefetchedDownload = prefetchedDownload,
                    ),
                    pulser!!, cancelKb,
                )
            }
        } catch (e: CancellationException) {
            // Kill yt-dlp first so it stops chewing CPU/network while we issue the Telegram delete.
            prefetchedDownload?.cancel()
            tmId?.let { id ->
                withContext(NonCancellable) {
                    runCatching { sender.deleteMessage(chatId, id) }
                }
            }
            throw e
        } finally {
            prefetchedDownload?.cancel()
            markerStore.delete(cancelToken)
            jobs.remove(cancelToken)
            pulser?.close()
        }
    }

    private suspend fun resolveLinks(
        urlEntities: List<MessageEntity>,
        validLinks: List<MessageEntity>,
        chatId: Long,
        onDetected: suspend () -> Unit,
        onPartial: suspend (String) -> Unit,
    ): LinkResolution? {
        val odesilDetections = urlEntities.mapNotNull { odesilService.detect(it) }
        val links = odesilDetections.map { linkBuilder.mapOdesilResponse(it.odesilResponse) }

        if (links.isEmpty() && validLinks.isEmpty()) {
            return null
        }

        onDetected()

        lateinit var linksMessage: String
        if (!links.isEmpty()) {
            linksMessage = if (links.size == 1) {
                links.first()
            } else {
                links.mapIndexed { index, l -> "${index + 1}. $l" }.joinToString(separator = "\n\n")
            }
        } else if (!validLinks.isEmpty()) {
            val validLink = validLinks.first()
            val meta = downloader.getVideoMeta(validLink.text)
            val displayTitle = linkBuilder.formatTitleWithDuration(meta)
            val partial = "$displayTitle\n<a href=\"${validLink.text}\">${validLink.text}</a>"

            // Early flush: show title + source link as soon as getVideoMeta returns, BEFORE the
            // ~17s ytSearch + Odesli chain runs. Without this the user stares at "Downloading…"
            // until the whole metadata pipeline finishes.
            onPartial(partial)

            val isMusicChat = chatsAndPlaylistNames[chatId]?.contains("music", ignoreCase = true) == true
            val odesilFromTitle = if (isMusicChat && linkBuilder.isVkOrRutube(validLink.text) && meta.title.isNotBlank()) {
                val searchQuery = linkBuilder.stripAnnotations(meta.title)
                downloader.ytSearchFirst(searchQuery, meta.durationSec)?.let { ytUrl ->
                    logger.info("VK/RuTube in music chat: probing Odesli via YT search '$ytUrl'")
                    odesilService.detect(ytUrl)
                }
            } else null
            linksMessage = if (odesilFromTitle != null) {
                linkBuilder.mapOdesilResponse(odesilFromTitle) + "\n<a href=\"${validLink.text}\">${validLink.text}</a>"
            } else {
                partial
            }
        }

        val youtubeFromMessage = linkBuilder.extractFirstUrlByText(linksMessage, "Youtube")
        val ytSearchUrl: String? = if (youtubeFromMessage == null && odesilDetections.isNotEmpty()) {
            val firstDetection = odesilDetections.first().odesilResponse
            val data = firstDetection.entitiesByUniqueId[firstDetection.entityUniqueId]
            val query = listOfNotNull(data?.artistName, data?.title)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            if (query.isNotBlank()) downloader.ytSearchFirst(query) else null
        } else null
        if (ytSearchUrl != null) {
            val ytLink = "<a href=\"$ytSearchUrl\">YouTube search</a>"
            val parts = linksMessage.split("\n", limit = 2)
            linksMessage = if (parts.size == 2) "${parts[0]}\n$ytLink | ${parts[1]}" else "$linksMessage\n$ytLink"
        }

        return LinkResolution("$linksMessage", youtubeFromMessage ?: ytSearchUrl)
    }

    private suspend fun sendStatusMessage(
        chatId: Long,
        text: String,
        replyToMessageId: Int,
        kb: InlineKeyboardMarkup,
        cancelToken: String,
        url: String?,
    ): Int {
        val sendMessage = SendMessage.builder()
            .chatId(chatId.toString())
            .text(text)
            .parseMode("HTML")
            .replyToMessageId(replyToMessageId)
            .linkPreviewOptions(LinkPreviewOptions.builder().isDisabled(true).build())
            .disableNotification(true)
            .replyMarkup(kb)
            .build()
        val id = telegramClient.executeAsync(sendMessage).await().messageId
        markerStore.write(cancelToken, JobMarkerStore.JobMarker(chatId, id, url, System.currentTimeMillis()))
        return id
    }

    private suspend fun processCommands(update: Update, command: String) {
        when (command) {
            "/help" -> sendHelp(update)
        }
    }

    private suspend fun sendHelp(update: Update) {
        logger.info("sending help")
        telegramClient.executeAsync(SendMessage(update.message.chatId.toString(), helpMessage)).await()
    }

    private fun getCommand(entities: List<MessageEntity>): String? {
        val entityText = entities.firstOrNull { entity -> entity.type == "bot_command" }?.text
        return if (entityText != null && (!entityText.contains("@") || entityText.contains(botName)))
            entityText.split("@").first()
        else {
            null
        }
    }

    private fun getResource(name: String): String {
        return this::class.java.classLoader.getResource(name)?.readText()
            ?: throw IllegalStateException("Resource $name is not found")
    }

}
