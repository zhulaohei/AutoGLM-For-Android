package com.kevinluo.autoglm.util

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.kevinluo.autoglm.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Manages log file operations including writing, rotation, cleanup, and export.
 *
 * Logs are stored in the app's private files directory under "logs/".
 * Each day's logs are stored in a separate file named "autoglm_YYYY-MM-DD.log".
 *
 * Features:
 * - Automatic daily log rotation
 * - File size limit with rotation
 * - Automatic cleanup of old logs
 * - Export logs as a shareable zip file
 */
object LogFileManager {

    private const val LOG_DIR = "logs"
    private const val LOG_FILE_PREFIX = "autoglm_"
    private const val LOG_FILE_EXTENSION = ".log"
    private const val MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024L // 5MB
    private const val DEFAULT_KEEP_DAYS = 7
    private const val DATE_FORMAT = "yyyy-MM-dd"
    private const val TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss.SSS"
    private const val EXPORT_TIMESTAMP_FORMAT = "yyyyMMdd_HHmmss"

    private val dateFormat = SimpleDateFormat(DATE_FORMAT, Locale.getDefault())
    private val timestampFormat = SimpleDateFormat(TIMESTAMP_FORMAT, Locale.getDefault())
    private val exportTimestampFormat = SimpleDateFormat(EXPORT_TIMESTAMP_FORMAT, Locale.getDefault())

    @Volatile
    private var appContext: Context? = null
    
    @Volatile
    private var isEnabled: Boolean = true

    private val writeLock = Any()

    /**
     * Initializes the LogFileManager with application context.
     * Should be called once during application startup.
     *
     * @param context Application context
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        // Clean up old logs on init
        cleanupOldLogs()
    }

    /**
     * Enables or disables file logging.
     *
     * @param enabled Whether file logging should be enabled
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
    }

    /**
     * Writes a log entry to the current day's log file.
     *
     * @param level Log level (VERBOSE, DEBUG, INFO, WARN, ERROR)
     * @param tag Component tag
     * @param message Log message
     * @param throwable Optional throwable to include stack trace
     */
    fun writeLog(level: String, tag: String, message: String, throwable: Throwable? = null) {
        if (!isEnabled) return
        val context = appContext ?: return

        synchronized(writeLock) {
            try {
                val logDir = getLogDirectory(context)
                val logFile = getCurrentLogFile(logDir)

                // Rotate if file is too large
                if (logFile.exists() && logFile.length() > MAX_FILE_SIZE_BYTES) {
                    rotateLogFile(logFile)
                }

                val timestamp = timestampFormat.format(Date())
                val logEntry = buildString {
                    append("$timestamp [$level] $tag: $message")
                    throwable?.let {
                        append("\n")
                        append(it.stackTraceToString())
                    }
                    append("\n")
                }

                FileWriter(logFile, true).use { writer ->
                    writer.write(logEntry)
                }
            } catch (e: Exception) {
                // Silently fail - don't want logging to crash the app
                e.printStackTrace()
            }
        }
    }

    /**
     * Gets all log files in the log directory.
     *
     * @return List of log files sorted by name (newest first)
     */
    fun getLogFiles(): List<File> {
        val context = appContext ?: return emptyList()
        val logDir = getLogDirectory(context)
        
        return logDir.listFiles { file ->
            file.isFile && file.name.startsWith(LOG_FILE_PREFIX) && file.name.endsWith(LOG_FILE_EXTENSION)
        }?.sortedByDescending { it.name } ?: emptyList()
    }

    /**
     * Exports all logs as a zip file and returns a share intent.
     *
     * @param context Context for file operations
     * @return Share intent for the exported zip file, or null if export failed
     */
    fun exportLogs(context: Context): Intent? {
        return try {
            val exportDir = File(context.cacheDir, "export")
            exportDir.mkdirs()

            val timestamp = exportTimestampFormat.format(Date())
            val zipFile = File(exportDir, "autoglm_logs_$timestamp.zip")

            ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->
                // Add device info
                val deviceInfo = getDeviceInfo(context)
                zipOut.putNextEntry(ZipEntry("device_info.txt"))
                zipOut.write(deviceInfo.toByteArray())
                zipOut.closeEntry()

                // Add log files
                getLogFiles().forEach { logFile ->
                    zipOut.putNextEntry(ZipEntry(logFile.name))
                    logFile.inputStream().use { input ->
                        input.copyTo(zipOut)
                    }
                    zipOut.closeEntry()
                }
            }

            // Create share intent
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                zipFile
            )

            Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "AutoGLM Debug Logs")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to export logs", e)
            null
        }
    }

    /**
     * Cleans up log files older than the specified number of days.
     *
     * @param keepDays Number of days to keep logs (default: 7)
     */
    fun cleanupOldLogs(keepDays: Int = DEFAULT_KEEP_DAYS) {
        val context = appContext ?: return

        try {
            val cutoffDate = System.currentTimeMillis() - (keepDays * 24 * 60 * 60 * 1000L)
            val logDir = getLogDirectory(context)

            logDir.listFiles { file ->
                file.isFile && file.name.startsWith(LOG_FILE_PREFIX)
            }?.forEach { file ->
                if (file.lastModified() < cutoffDate) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Clears all log files.
     */
    fun clearAllLogs() {
        val context = appContext ?: return

        try {
            val logDir = getLogDirectory(context)
            logDir.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Gets device and app information for debugging.
     *
     * @param context Context for accessing app info
     * @return Formatted device info string
     */
    fun getDeviceInfo(context: Context): String {
        return buildString {
            appendLine("=== AutoGLM Debug Info ===")
            appendLine()
            appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Package: ${BuildConfig.APPLICATION_ID}")
            appendLine("Build Type: ${BuildConfig.BUILD_TYPE}")
            appendLine()
            appendLine("=== Device Info ===")
            appendLine()
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("Model: ${Build.MODEL}")
            appendLine("Device: ${Build.DEVICE}")
            appendLine("Product: ${Build.PRODUCT}")
            appendLine()
            appendLine("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Security Patch: ${Build.VERSION.SECURITY_PATCH}")
            appendLine()
            appendLine("=== System Info ===")
            appendLine()
            appendLine("Display: ${Build.DISPLAY}")
            appendLine("Hardware: ${Build.HARDWARE}")
            appendLine("Board: ${Build.BOARD}")
            appendLine()
            appendLine("Generated: ${timestampFormat.format(Date())}")
        }
    }

    /**
     * Gets the total size of all log files.
     *
     * @return Total size in bytes
     */
    fun getTotalLogSize(): Long {
        return getLogFiles().sumOf { it.length() }
    }

    /**
     * Formats a byte size to human-readable string.
     *
     * @param bytes Size in bytes
     * @return Formatted string (e.g., "1.5 MB")
     */
    fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    private fun getLogDirectory(context: Context): File {
        val logDir = File(context.filesDir, LOG_DIR)
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        return logDir
    }

    private fun getCurrentLogFile(logDir: File): File {
        val today = dateFormat.format(Date())
        return File(logDir, "$LOG_FILE_PREFIX$today$LOG_FILE_EXTENSION")
    }

    private fun rotateLogFile(logFile: File) {
        val timestamp = System.currentTimeMillis()
        val rotatedName = logFile.name.replace(LOG_FILE_EXTENSION, "_$timestamp$LOG_FILE_EXTENSION")
        val rotatedFile = File(logFile.parentFile, rotatedName)
        logFile.renameTo(rotatedFile)
    }

    private const val TAG = "LogFileManager"
}
