package com.nirzor.voicebubble.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.nirzor.voicebubble.BuildConfig
import com.nirzor.voicebubble.R
import com.nirzor.voicebubble.VoiceBubbleApp
import com.nirzor.voicebubble.speech.SpeechEngine
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val changelog: String,
    val apkDownloadUrl: String,
    val sha256DownloadUrl: String?,
    val expectedSize: Long?
)

sealed interface UpdateStatus {
    object Idle : UpdateStatus
    object Checking : UpdateStatus
    data class UpToDate(val currentVersion: String) : UpdateStatus
    data class UpdateAvailable(val info: UpdateInfo) : UpdateStatus
    data class Downloading(val progressPercent: Int, val info: UpdateInfo) : UpdateStatus
    object Verifying : UpdateStatus
    data class ReadyToInstall(val apkFile: File) : UpdateStatus
    object Installing : UpdateStatus
    data class Installed(val version: String) : UpdateStatus
    data class Error(
        val message: String,
        val canRetry: Boolean = true,
        val installerStatusCode: Int? = null,
        val installerSessionId: Int? = null,
        val targetVersionCode: Int? = null
    ) : UpdateStatus
}

@JsonClass(generateAdapter = true)
data class GitHubReleaseResponse(
    val tag_name: String,
    val body: String?,
    val assets: List<GitHubAsset>?
)

@JsonClass(generateAdapter = true)
data class GitHubAsset(
    val name: String,
    val browser_download_url: String,
    val size: Long?
)

class UpdateManager(private val context: Context) {

    private val TAG = "UpdateManager"
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()
    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private var cachedApkFile: File? = null
    private val operationLock = Any()
    @Volatile private var operationInProgress = false
    @Volatile private var lastDownloadInfo: UpdateInfo? = null
    @Volatile private var verifiedApkInfo: UpdateInfo? = null
    @Volatile private var verifiedApkSha256: String? = null
    private val updatePrefs by lazy {
        context.getSharedPreferences("updater_state", Context.MODE_PRIVATE)
    }

    init {
        recoverInterruptedUpdate()
    }

    companion object {
        private const val OWNER = "engrnirzorme02"
        private const val REPO = "NIRZOR-Voice-Typing-floating"
        private const val APK_NAME = "voicebubble.apk"
        private const val SHA_NAME = "voicebubble.apk.sha256"
        private const val KEY_PENDING_VERSION_CODE = "pending_version_code"
        private const val KEY_PENDING_SESSION_ID = "pending_session_id"

        @Volatile
        private var INSTANCE: UpdateManager? = null

        fun getInstance(context: Context): UpdateManager {
            return INSTANCE ?: synchronized(this) {
                val instance = UpdateManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }

        fun parseVersionCode(versionName: String): Int {
            val clean = versionName.removePrefix("v").trim()
            val parts = clean.split(".").map { it.toIntOrNull() ?: 0 }
            val major = parts.getOrElse(0) { 1 }
            val minor = parts.getOrElse(1) { 0 }
            val patch = parts.getOrElse(2) { 0 }
            return major * 10000 + minor * 100 + patch
        }
    }

    private fun beginOperation(): Boolean = synchronized(operationLock) {
        if (operationInProgress) return@synchronized false
        operationInProgress = true
        _isBusy.value = true
        true
    }

    private fun finishOperation() = synchronized(operationLock) {
        operationInProgress = false
        _isBusy.value = false
    }

    private fun recoverInterruptedUpdate() {
        val pendingCode = updatePrefs.getInt(KEY_PENDING_VERSION_CODE, 0)
        if (pendingCode == 0) {
            File(context.cacheDir, "voicebubble_update.apk").delete()
            return
        }
        val installed = installedPackageInfo()
        if (installed != null && installed.versionCodeCompat() >= pendingCode) {
            clearPendingInstall()
            _status.value = UpdateStatus.Installed(installed.versionName ?: BuildConfig.VERSION_NAME)
            return
        }
        val pendingSessionId = updatePrefs.getInt(KEY_PENDING_SESSION_ID, -1)
        val sessionInfo = try {
            context.packageManager.packageInstaller.mySessions.firstOrNull { it.sessionId == pendingSessionId }
        } catch (_: Exception) {
            null
        }
        val recoveryDecision = installerRecoveryDecision(
            sessionFound = sessionInfo != null,
            isSealed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) sessionInfo?.isSealed else null,
            isCommitted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) sessionInfo?.isCommitted else null
        )
        if (recoveryDecision == InstallerRecoveryDecision.RESTORE_AWAITING_RESULT) {
            operationInProgress = true
            _isBusy.value = true
            _status.value = UpdateStatus.Installing
        } else {
            if (sessionInfo != null) {
                try {
                    context.packageManager.packageInstaller.abandonSession(pendingSessionId)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not abandon interrupted installer session", e)
                }
            }
            clearPendingInstall()
            File(context.cacheDir, "voicebubble_update.apk").delete()
            _status.value = UpdateStatus.Error("আপডেটটি সম্পন্ন হয়নি। আবার চেষ্টা করুন।")
        }
    }

    private fun clearPendingInstall() {
        updatePrefs.edit().remove(KEY_PENDING_VERSION_CODE).remove(KEY_PENDING_SESSION_ID).apply()
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.versionCodeCompat(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode.toInt() else versionCode

    private fun installedPackageInfo(): PackageInfo? = try {
        context.packageManager.getPackageInfo(
            context.packageName,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        )
    } catch (_: Exception) {
        null
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val moshi: Moshi by lazy {
        Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
    }

    fun checkForUpdates(isAutoCheck: Boolean = false) {
        val prefs = VoiceBubbleApp.instance.preferences
        val now = System.currentTimeMillis()

        if (isAutoCheck) {
            val lastCheck = prefs.lastUpdateCheckTime.value
            val oneDayMillis = 24 * 60 * 60 * 1000L
            if (now - lastCheck < oneDayMillis) {
                return
            }
        }

        if (!beginOperation()) return
        lastDownloadInfo = null

        _status.value = UpdateStatus.Checking

        scope.launch {
            try {
                val apiUrl = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
                val request = Request.Builder()
                    .url(apiUrl)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "VoiceBubble-Updater")
                    .build()

                val response = httpClient.newCall(request).execute()

                if (response.code == 403) {
                    // Fallback on 403 rate limit via HEAD request without following redirects
                    response.close()
                    val fallbackInfo = checkViaHeadRedirect()
                    if (fallbackInfo != null) {
                        evaluateRelease(fallbackInfo)
                    } else {
                        _status.value = UpdateStatus.Error("GitHub API রেট লিমিট অতিক্রম করেছে। কিছুক্ষণ পরে চেষ্টা করুন।")
                    }
                    return@launch
                }

                if (response.code == 404) {
                    response.close()
                    _status.value = UpdateStatus.Error("রিলিজ তথ্য পাওয়া যায়নি (HTTP 404)")
                    return@launch
                }

                if (!response.isSuccessful) {
                    val code = response.code
                    response.close()
                    _status.value = UpdateStatus.Error("আপডেট চেক ব্যর্থ হয়েছে (HTTP $code)")
                    return@launch
                }

                val responseBody = response.body?.string() ?: ""
                response.close()

                val adapter = moshi.adapter(GitHubReleaseResponse::class.java)
                val release = adapter.fromJson(responseBody)

                if (release == null) {
                    _status.value = UpdateStatus.Error("রিলিজ ডাটা পার্স করা যায়নি")
                    return@launch
                }

                val tagName = release.tag_name
                val cleanTag = tagName.removePrefix("v").trim()
                val latestCode = parseReleaseVersionCode(cleanTag)
                if (latestCode == null) {
                    _status.value = UpdateStatus.Error("রিলিজ সংস্করণের তথ্য অবৈধ")
                    return@launch
                }
                val changelog = release.body ?: ""

                val apkAsset = release.assets?.find { it.name == APK_NAME }
                val shaAsset = release.assets?.find { it.name == SHA_NAME }

                val apkUrl = apkAsset?.browser_download_url
                    ?: "https://github.com/$OWNER/$REPO/releases/download/$tagName/$APK_NAME"
                val shaUrl = shaAsset?.browser_download_url
                    ?: "https://github.com/$OWNER/$REPO/releases/download/$tagName/$SHA_NAME"

                if (!isTrustedReleaseAssetUrl(apkUrl) || !isTrustedReleaseAssetUrl(shaUrl)) {
                    _status.value = UpdateStatus.Error("আপডেটের ডাউনলোড ঠিকানা যাচাই করা যায়নি")
                    return@launch
                }

                val updateInfo = UpdateInfo(
                    versionName = cleanTag,
                    versionCode = latestCode,
                    changelog = changelog,
                    apkDownloadUrl = apkUrl,
                    sha256DownloadUrl = shaUrl,
                    expectedSize = apkAsset?.size
                )

                evaluateRelease(updateInfo)

            } catch (e: Exception) {
                Log.e(TAG, "Update check failed", e)
                _status.value = UpdateStatus.Error("নেটওয়ার্ক ত্রুটি: ইন্টারনেট কানেকশন চেক করুন")
            } finally {
                finishOperation()
            }
        }
    }

    /** Re-check releases after an error; the UI should call this instead of merely clearing status. */
    fun retry() {
        val failed = _status.value as? UpdateStatus.Error
        val retryInfo = lastDownloadInfo
        if (failed != null && retryInfo != null && retryInfo.versionCode > BuildConfig.VERSION_CODE) {
            startDownload(retryInfo)
        } else {
            checkForUpdates(isAutoCheck = false)
        }
    }

    private fun checkViaHeadRedirect(): UpdateInfo? {
        return try {
            val headClient = OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(10, TimeUnit.SECONDS)
                .build()

            val headRequest = Request.Builder()
                .url("https://github.com/$OWNER/$REPO/releases/latest")
                .head()
                .build()

            val headResponse = headClient.newCall(headRequest).execute()
            val location = headResponse.header("Location") ?: ""
            headResponse.close()

            if (location.isNotBlank()) {
                val tag = location.substringAfterLast("/")
                val cleanTag = tag.removePrefix("v").trim()
                val code = parseReleaseVersionCode(cleanTag) ?: return null

                UpdateInfo(
                    versionName = cleanTag,
                    versionCode = code,
                    changelog = "নতুন সংস্করণ উপলব্ধ",
                    apkDownloadUrl = "https://github.com/$OWNER/$REPO/releases/download/$tag/$APK_NAME",
                    sha256DownloadUrl = "https://github.com/$OWNER/$REPO/releases/download/$tag/$SHA_NAME",
                    expectedSize = null
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fallback HEAD check failed", e)
            null
        }
    }

    private fun evaluateRelease(info: UpdateInfo) {
        VoiceBubbleApp.instance.preferences.setLastUpdateCheckTime(System.currentTimeMillis())
        if (info.versionCode > BuildConfig.VERSION_CODE) {
            _status.value = UpdateStatus.UpdateAvailable(info)
        } else {
            _status.value = UpdateStatus.UpToDate(BuildConfig.VERSION_NAME)
        }
    }

    fun startDownload(info: UpdateInfo) {
        lastDownloadInfo = info
        val began = synchronized(SpeechEngine.sessionUpdateLock) {
            if (SpeechEngine.isAnySessionActive) {
                _status.value = UpdateStatus.Error(context.getString(R.string.updater_blocked_busy))
                false
            } else {
                beginOperation()
            }
        }
        if (!began) return

        scope.launch {
            var awaitingInstallerResult = false
            try {
                if (parseReleaseVersionCode(info.versionName) != info.versionCode || info.versionCode <= BuildConfig.VERSION_CODE) {
                    _status.value = UpdateStatus.Error("আপডেটের সংস্করণ যাচাই করা যায়নি")
                    return@launch
                }
                if (!isTrustedReleaseAssetUrl(info.apkDownloadUrl) ||
                    info.sha256DownloadUrl == null || !isTrustedReleaseAssetUrl(info.sha256DownloadUrl)) {
                    _status.value = UpdateStatus.Error("আপডেটের ডাউনলোড ঠিকানা যাচাই করা যায়নি")
                    return@launch
                }
                if (info.expectedSize != null && info.expectedSize <= 0L) {
                    _status.value = UpdateStatus.Error("আপডেট ফাইলের আকার যাচাই করা যায়নি")
                    return@launch
                }
                _status.value = UpdateStatus.Downloading(0, info)

                val shaRequest = Request.Builder().url(info.sha256DownloadUrl).build()
                val shaResponse = httpClient.newCall(shaRequest).execute()
                val expectedSha256 = shaResponse.use { response ->
                    if (!response.isSuccessful) null else parseSha256Checksum(response.body?.string())
                }
                if (expectedSha256 == null) {
                    _status.value = UpdateStatus.Error("SHA-256 চেকসাম পাওয়া যায়নি বা অবৈধ")
                    return@launch
                }

                // 2. Download APK file
                val apkFile = File(context.cacheDir, "voicebubble_update.apk")
                if (apkFile.exists()) apkFile.delete()

                val apkRequest = Request.Builder().url(info.apkDownloadUrl).build()
                val apkResponse = httpClient.newCall(apkRequest).execute()

                var downloadedBytes = 0L
                apkResponse.use { response ->
                    if (!response.isSuccessful) {
                        _status.value = UpdateStatus.Error("APK ডাউনলোড ব্যর্থ হয়েছে (HTTP ${response.code})")
                        return@launch
                    }
                    val body = response.body ?: run {
                        _status.value = UpdateStatus.Error("ডাউনলোড রেসপন্স খালি ছিল")
                        return@launch
                    }
                    val totalBytes = body.contentLength()
                    if (info.expectedSize != null && totalBytes >= 0L && totalBytes != info.expectedSize) {
                        _status.value = UpdateStatus.Error("আপডেট ফাইলের আকার মেলেনি")
                        return@launch
                    }
                    body.byteStream().use { input ->
                        FileOutputStream(apkFile).use { output ->
                            val buffer = ByteArray(8192)
                            var lastProgress = 0
                            while (true) {
                                val bytesRead = input.read(buffer)
                                if (bytesRead < 0) break
                                output.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead
                                if (totalBytes > 0) {
                                    val progress = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                                    if (progress != lastProgress) {
                                        lastProgress = progress
                                        _status.value = UpdateStatus.Downloading(progress, info)
                                    }
                                }
                            }
                        }
                    }
                }
                if (downloadedBytes == 0L || (info.expectedSize != null && downloadedBytes != info.expectedSize)) {
                    apkFile.delete()
                    _status.value = UpdateStatus.Error("আপডেট ফাইলের আকার মেলেনি")
                    return@launch
                }

                // 3. Verify SHA256
                _status.value = UpdateStatus.Verifying

                if (!calculateFileSha256(apkFile).equals(expectedSha256, ignoreCase = true)) {
                    apkFile.delete()
                    _status.value = UpdateStatus.Error(context.getString(R.string.updater_sha_mismatch))
                    return@launch
                }
                if (!validateDownloadedApk(apkFile, info)) {
                    apkFile.delete()
                    _status.value = UpdateStatus.Error("ডাউনলোড করা অ্যাপের পরিচয় বা স্বাক্ষর যাচাই করা যায়নি")
                    return@launch
                }

                cachedApkFile = apkFile
                verifiedApkInfo = info
                verifiedApkSha256 = expectedSha256
                // The user already selected "Download & Install". Continue directly
                // to PackageInstaller after the downloaded APK passes SHA-256 validation.
                _status.value = UpdateStatus.ReadyToInstall(apkFile)
                Log.i(TAG, "APK download and checksum verification succeeded; starting installation")
                awaitingInstallerResult = installApkInternal(apkFile, info.versionCode)

            } catch (e: Exception) {
                Log.w(TAG, "Update download or validation failed", e)
                _status.value = UpdateStatus.Error("ডাউনলোড বা যাচাই ব্যর্থ হয়েছে। আবার চেষ্টা করুন।")
            } finally {
                if (!awaitingInstallerResult) finishOperation()
            }
        }
    }

    private fun calculateFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun validateDownloadedApk(apkFile: File, info: UpdateInfo): Boolean {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archive = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags) ?: return false
        val installed = installedPackageInfo() ?: return false
        if (archive.packageName != context.packageName || archive.versionCodeCompat() != info.versionCode) return false
        if (archive.versionName != info.versionName) return false

        val archiveSigners = signingCertificates(archive)
        val installedSigners = signingCertificates(installed)
        return isApkMetadataTrusted(
            archivePackageName = archive.packageName,
            archiveVersionName = archive.versionName,
            archiveVersionCode = archive.versionCodeCompat(),
            expectedPackageName = context.packageName,
            expectedVersionName = info.versionName,
            expectedVersionCode = info.versionCode,
            archiveSigners = archiveSigners,
            installedSigners = installedSigners
        )
    }

    @Suppress("DEPRECATION")
    private fun signingCertificates(packageInfo: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners
        } else {
            packageInfo.signatures
        }
        return signatures.orEmpty().map { it.toCharsString() }.toSet()
    }


    fun installApk(apkFile: File): Boolean {
        val info = lastDownloadInfo ?: return false
        val validatedInfo = verifiedApkInfo ?: return false
        val validatedSha = verifiedApkSha256 ?: return false
        val cached = cachedApkFile ?: return false
        val fileIsStillTrusted = try {
            info == validatedInfo && apkFile.exists() && cached.exists() &&
                apkFile.canonicalFile == cached.canonicalFile &&
                calculateFileSha256(apkFile).equals(validatedSha, ignoreCase = true) &&
                validateDownloadedApk(apkFile, info)
        } catch (e: Exception) {
            Log.w(TAG, "Cached APK revalidation failed", e)
            false
        }
        if (!fileIsStillTrusted) {
            _status.value = UpdateStatus.Error("ইনস্টলার ফাইলের যাচাইকরণ ব্যর্থ হয়েছে। আবার ডাউনলোড করুন।")
            return false
        }
        val began = synchronized(SpeechEngine.sessionUpdateLock) {
            if (SpeechEngine.isAnySessionActive) {
                _status.value = UpdateStatus.Error(context.getString(R.string.updater_blocked_busy))
                false
            } else {
                beginOperation()
            }
        }
        if (!began) return false
        val started = installApkInternal(apkFile, info.versionCode)
        if (!started) finishOperation()
        return started
    }

    private fun installApkInternal(apkFile: File, targetVersionCode: Int?): Boolean {
        if (!apkFile.exists()) {
            _status.value = UpdateStatus.Error("ইনস্টলার ফাইল খুঁজে পাওয়া যায়নি")
            return false
        }

        _status.value = UpdateStatus.Installing

        var packageInstaller: PackageInstaller? = null
        var session: PackageInstaller.Session? = null
        var sessionId = -1
        try {
            packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }

            sessionId = packageInstaller.createSession(params)
            updatePrefs.edit()
                .putInt(KEY_PENDING_VERSION_CODE, targetVersionCode ?: BuildConfig.VERSION_CODE)
                .putInt(KEY_PENDING_SESSION_ID, sessionId)
                .apply()
            session = packageInstaller.openSession(sessionId)

            session.openWrite("voicebubble_package", 0, apkFile.length()).use { out ->
                apkFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            }

            val intent = Intent(context, UpdateInstallReceiver::class.java).apply {
                action = UpdateInstallReceiver.ACTION_INSTALL_STATUS
                `package` = context.packageName // Explicit package per Android 14+ rules
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val sessionToCommit = session
                ?: throw IllegalStateException("Installer session is not available")
            val committed = synchronized(SpeechEngine.sessionUpdateLock) {
                if (SpeechEngine.isAnySessionActive) {
                    false
                } else {
                    sessionToCommit.commit(pendingIntent.intentSender)
                    true
                }
            }
            if (!committed) {
                try { sessionToCommit.close() } catch (_: Exception) { }
                session = null
                try { packageInstaller.abandonSession(sessionId) } catch (_: Exception) { }
                clearPendingInstall()
                _status.value = UpdateStatus.Error(context.getString(R.string.updater_blocked_busy))
                finishOperation()
                return false
            }
            try { sessionToCommit.close() } catch (e: Exception) {
                Log.w(TAG, "Committed installer session close failed", e)
            }
            session = null
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Install failed to launch session", e)
            try { session?.close() } catch (_: Exception) { }
            if (sessionId >= 0) {
                try { packageInstaller?.abandonSession(sessionId) } catch (_: Exception) { }
            }
            clearPendingInstall()
            _status.value = UpdateStatus.Error("ইনস্টলার সেশন শুরু করতে ব্যর্থ হয়েছে। আবার চেষ্টা করুন।")
            finishOperation()
            return false
        }
    }

    fun onInstallSuccess() {
        cachedApkFile?.delete()
        cachedApkFile = null
        val expectedCode = updatePrefs.getInt(KEY_PENDING_VERSION_CODE, 0)
        val installed = installedPackageInfo()
        val installedCode = installed?.versionCodeCompat() ?: 0
        clearPendingInstall()
        finishOperation()
        if (installed == null || installedCode == 0 || (expectedCode != 0 && installedCode < expectedCode)) {
            _status.value = UpdateStatus.Error("ইনস্টলেশন সম্পন্ন হয়েছে কি না যাচাই করা যায়নি। অ্যাপটি আবার খুলুন।")
            return
        }
        _status.value = UpdateStatus.Installed(installed.versionName ?: BuildConfig.VERSION_NAME)
        // MainActivity records the new version on launch, preserving its update-success toast.
    }

    fun onInstallFailure(reason: String, sessionId: Int = -1) {
        if (sessionId >= 0) {
            try {
                context.packageManager.packageInstaller.abandonSession(sessionId)
            } catch (e: Exception) {
                Log.w(TAG, "Could not abandon failed confirmation session", e)
            }
        }
        cachedApkFile?.delete()
        cachedApkFile = null
        clearPendingInstall()
        finishOperation()
        _status.value = UpdateStatus.Error(reason)
    }

    fun onInstallFailure(statusCode: Int, sessionId: Int) {
        val targetCode = updatePrefs.getInt(KEY_PENDING_VERSION_CODE, 0).takeIf { it > 0 }
        cachedApkFile?.delete()
        cachedApkFile = null
        clearPendingInstall()
        finishOperation()
        _status.value = UpdateStatus.Error(
            message = installerFailureMessage(statusCode),
            installerStatusCode = statusCode,
            installerSessionId = sessionId.takeIf { it >= 0 },
            targetVersionCode = targetCode
        )
    }

    fun reset() {
        if (operationInProgress) return
        _status.value = UpdateStatus.Idle
    }
}
