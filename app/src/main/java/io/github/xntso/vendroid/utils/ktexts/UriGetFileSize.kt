package io.github.xntso.vendroid.utils.ktexts

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.database.SQLException
import android.net.Uri
import android.provider.OpenableColumns
import io.github.xntso.vendroid.plugins.telemetry.Telemetry
import java.io.File

// https://github.com/android-rcs/rcsjta/blob/master/RI/src/com/gsma/rcs/ri/utils/FileUtils.java#L214

fun Uri.getFileSize(context: Context): Long {
    when (this.scheme) {
        ContentResolver.SCHEME_FILE -> {
            val f = File(this.getFilePath(context)!!)
            return f.length()
        }

        ContentResolver.SCHEME_CONTENT -> {
            var cursor: Cursor? = null

            try {
                cursor = context.contentResolver.query(this, null, null, null, null)
            } catch (e: Exception) {
                Telemetry.captureException("getFileSize: Failed to query content resolver", e)
            }

            cursor.use {
                if (it == null) {
                    throw SQLException("Failed to query file $this")
                }
                return if (it.moveToFirst()) {
                    java.lang.Long.valueOf(
                        it.getString(
                            it.getColumnIndexOrThrow(OpenableColumns.SIZE)
                        )
                    )
                } else {
                    throw IllegalArgumentException(
                        "Error in retrieving this size form the URI"
                    )
                }
            }
        }

        else -> throw IllegalArgumentException("Unsupported URI scheme")
    }
}