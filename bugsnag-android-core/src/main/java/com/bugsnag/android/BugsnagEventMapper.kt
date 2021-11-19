package com.bugsnag.android

import com.bugsnag.android.internal.DateUtils
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal class BugsnagEventMapper(
    private val logger: Logger
) {

    internal fun convertAppWithState(app: Map<String, Any?>): AppWithState {
        return AppWithState(
            app["binaryArch"] as? String,
            app["id"] as? String,
            app["releaseStage"] as? String,
            app["version"] as? String,
            app["codeBundleId"] as? String,
            app["buildUUID"] as? String,
            app["type"] as? String,
            (app["versionCode"] as? Number)?.toInt(),
            (app["duration"] as? Number)?.toLong(),
            (app["durationInForeground"] as? Number)?.toLong(),
            app["inForeground"] as? Boolean,
            app["isLaunching"] as? Boolean
        )
    }

    @Suppress("UNCHECKED_CAST")
    internal fun convertDeviceWithState(device: Map<String, Any?>): DeviceWithState {
        return DeviceWithState(
            DeviceBuildInfo(
                device["manufacturer"] as? String,
                device["model"] as? String,
                device["osVersion"] as? String,
                null,
                null,
                null,
                null,
                null,
                (device["cpuAbi"] as? List<String>)?.toTypedArray()
            ),
            device["jailbroken"] as? Boolean,
            device["id"] as? String,
            device["locale"] as? String,
            (device["totalMemory"] as? Number)?.toLong(),
            (device["runtimeVersions"] as? Map<String, Any>)?.toMutableMap()
                ?: mutableMapOf(),
            (device["freeDisk"] as? Number)?.toLong(),
            (device["freeMemory"] as? Number)?.toLong(),
            device["orientation"] as? String,
            (device["time"] as? String)?.toDate()
        )
    }

    @Suppress("UNCHECKED_CAST")
    internal fun convertThread(thread: Map<String, Any?>): ThreadInternal {
        return ThreadInternal(
            (thread["id"] as? Number)?.toLong() ?: 0,
            thread.readEntry("name"),
            ThreadType.fromDescriptor(thread.readEntry("type")) ?: ThreadType.ANDROID,
            thread["errorReportingThread"] == true,
            thread.readEntry("state"),
            (thread["stacktrace"] as? List<Map<String, Any?>>)?.let { convertStacktrace(it) }
                ?: Stacktrace(emptyList())
        )
    }

    internal fun convertStacktrace(trace: List<Map<String, Any?>>): Stacktrace {
        return Stacktrace(trace.map { convertStackframe(it) })
    }

    internal fun convertStackframe(frame: Map<String, Any?>): Stackframe {
        val copy: MutableMap<String, Any?> = frame.toMutableMap()
        val lineNumber = frame["lineNumber"] as? Number
        copy["lineNumber"] = lineNumber?.toLong()

        (frame["frameAddress"] as? String)?.let {
            copy["frameAddress"] = java.lang.Long.decode(it)
        }

        (frame["symbolAddress"] as? String)?.let {
            copy["symbolAddress"] = java.lang.Long.decode(it)
        }

        (frame["loadAddress"] as? String)?.let {
            copy["loadAddress"] = java.lang.Long.decode(it)
        }

        (frame["isPC"] as? Boolean)?.let {
            copy["isPC"] = it
        }

        return Stackframe(copy)
    }

    internal fun deserializeSeverityReason(
        map: Map<in String, Any?>,
        unhandled: Boolean,
        severity: Severity?
    ): SeverityReason {
        val severityReason: Map<String, Any> = map.readEntry("severityReason")
        val unhandledOverridden: Boolean =
            severityReason.readEntry("unhandledOverridden")
        val type: String = severityReason.readEntry("type")
        val originalUnhandled = when {
            unhandledOverridden -> !unhandled
            else -> unhandled
        }

        val attrMap: Map<String, String>? = severityReason.readEntry("attributes")
        val entry = attrMap?.entries?.singleOrNull()
        return SeverityReason(
            type,
            severity,
            unhandled,
            originalUnhandled,
            entry?.value,
            entry?.key
        )
    }

    /**
     * Convenience method for getting an entry from a Map in the expected type, which
     * throws useful error messages if the expected type is not there.
     */
    private inline fun <reified T> Map<*, *>.readEntry(key: String): T {
        when (val value = get(key)) {
            is T -> return value
            null -> throw IllegalStateException("cannot find json property '$key'")
            else -> throw IllegalArgumentException(
                "json property '$key' not " +
                    "of expected type, found ${value.javaClass.name}"
            )
        }
    }

    // SimpleDateFormat isn't thread safe, cache one instance per thread as needed.
    private val ndkDateFormatHolder = object : ThreadLocal<DateFormat>() {
        override fun initialValue(): DateFormat {
            return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }
    }

    private fun String.toDate(): Date {
        return try {
            DateUtils.fromIso8601(this)
        } catch (pe: IllegalArgumentException) {
            ndkDateFormatHolder.get()!!.parse(this)
                ?: throw IllegalArgumentException("cannot parse date $this")
        }
    }
}
