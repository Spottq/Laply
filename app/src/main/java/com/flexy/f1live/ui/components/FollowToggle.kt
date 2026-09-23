package com.flexy.f1live.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.flexy.f1live.live.LiveUpdateController

/**
 * The one way the UI switches the Follow opt-in ("show the Live Update automatically"), shared by
 * the Follow button on the Live tab and the switch in Settings so both behave identically:
 * on asks for POST_NOTIFICATIONS first when needed and only opts in once it is granted - the
 * opt-in exists only to post a notification - then arms the alarm and starts the Live Update if a
 * session is on ([LiveUpdateController.follow]); off stops it and cancels the alarm.
 *
 * Returns `(enable) -> Unit`. Both screens render the same [LiveUpdateController.followEnabled]
 * flow, so neither needs to be told when the other changed it.
 */
@Composable
fun rememberFollowToggle(): (Boolean) -> Unit {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) LiveUpdateController.follow(context)
    }
    return { enable ->
        if (!enable) {
            LiveUpdateController.unfollow(context)
        } else {
            val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            if (needsPermission) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                LiveUpdateController.follow(context)
            }
        }
    }
}
